package com.example.serverrunner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile

// Manager: keeps ONE foreground notification + wake/wifi locks for all servers.
// Go binaries and cloudflared run as child processes; Python servers run in PyWorker<N> processes.
class ServerService : Service() {
    companion object {
        const val START = "start"; const val STOP = "stop"; const val STOP_ALL = "stopall"; const val BOOT = "boot"
    }

    private class Run {
        @Volatile var stopped = false
        @Volatile var server: Process? = null
        @Volatile var tunnel: Process? = null
        var slot = -1
    }

    private val runs = HashMap<String, Run>()
    private val conns = HashMap<Int, ServiceConnection>()
    private val workers: List<Class<*>> = listOf(
        PyWorker0::class.java, PyWorker1::class.java, PyWorker2::class.java,
        PyWorker3::class.java, PyWorker4::class.java, PyWorker5::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val fixedOnce = HashSet<String>()   // servers already auto-fixed for DB errors (one restart max)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun log(id: String, s: String) { try { Store.logFile(this, id).appendText(s + "\n") } catch (e: Exception) {} }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, startId: Int): Int {
        val act = i?.action
        val id = i?.getStringExtra("id")
        when (act) {
            START -> id?.let { fixedOnce.remove(it); startOne(it) }
            STOP -> id?.let { fixedOnce.remove(it); stopOne(it) }
            STOP_ALL -> { fixedOnce.clear(); runs.keys.toList().forEach { killRun(it) }; Store.setRunning(this, emptySet()) }
            // BOOT, or restarted by the system after being killed (null intent): resume everything wanted.
            else -> Store.running(this).forEach { startOne(it) }
        }
        val empty = Store.running(this).isEmpty()
        if (act != STOP && act != STOP_ALL) foreground()
        if (empty) {
            releaseLocks(); stopForeground(true); stopSelf()
            return START_NOT_STICKY
        }
        foreground()
        acquireLocks()
        return START_STICKY
    }

    // Swiping the app from recents must NOT stop the servers.
    override fun onTaskRemoved(rootIntent: Intent?) {}

    private fun foreground() {
        val ch = "srv"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Server", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra("stop", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Store.running(this).size
        startForeground(1, Notification.Builder(this, ch)
            .setContentTitle("Server is running")
            .setContentText("$n server ทำงานอยู่ในพื้นหลัง")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Stop all", stopPi).build())
            .build())
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "serverrunner:cpu")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "serverrunner:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}
        wakeLock = null; wifiLock = null
    }

    private fun startOne(id: String, note: String? = null) {
        if (runs.containsKey(id)) return
        val s = Store.load(this).firstOrNull { it.id == id }
        if (s == null) { Store.setRunning(this, Store.running(this) - id); return }
        Store.logFile(this, id).writeText("")
        val f = File(Store.dir(this, id), "server_file")
        if (!f.exists()) {
            log(id, "ยังไม่ได้เลือกไฟล์ server")
            Store.setRunning(this, Store.running(this) - id); return
        }
        note?.let { log(id, it) }
        val elf = isElf(f)
        val made = prepareDb(id, f, elf)
        if (made.isNotEmpty()) log(id, "[auto] พบว่า server ใช้ SQLite แต่ยังไม่มีฐานข้อมูล → สร้างให้แล้ว: ${made.joinToString(", ")}")
        val r = Run()
        if (elf) {
            Thread { runBinary(id, r, f, s.port) }.start()
        } else {
            val slot = workers.indices.firstOrNull { sl -> runs.values.none { it.slot == sl } }
            if (slot == null) {
                log(id, "Python server เต็ม (สูงสุด ${workers.size} ตัวพร้อมกัน)")
                Store.setRunning(this, Store.running(this) - id); return
            }
            r.slot = slot
            startPython(id, slot, f, s.port)
        }
        runs[id] = r
        if (id !in fixedOnce) watchDb(id, r, f, elf)
        Store.setRunning(this, Store.running(this) + id)
        Thread { runTunnel(id, r, s.port, s.token) }.start()
    }

    private fun startPython(id: String, slot: Int, f: File, port: Int) {
        val cls = workers[slot]
        startService(Intent(this, cls)
            .putExtra("path", f.path).putExtra("log", Store.logFile(this, id).path).putExtra("port", port))
        // Binding from this foreground service raises the worker's priority so it isn't killed in the background.
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, b: IBinder?) {}
            override fun onServiceDisconnected(n: ComponentName?) {}
        }
        conns[slot] = conn
        bindService(Intent(this, cls), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
    }

    // Does the server use SQLite? Python: look for sqlite in the source and quoted *.sqlite3/*.db paths.
    // Go binary: look for "sqlite" strings (paths are not recoverable, so only the default DB is created).
    // Creates missing files (an empty file is a valid empty SQLite DB) and their folders.
    private fun prepareDb(id: String, f: File, elf: Boolean): List<String> {
        if (f.length() > 40L * 1024 * 1024) return emptyList()
        val text = try { String(f.readBytes(), Charsets.ISO_8859_1) } catch (e: Exception) { return emptyList() }
        if (!text.contains("sqlite", ignoreCase = true)) return emptyList()
        val base = Store.dir(this, id)
        val names = LinkedHashSet<String>()
        if (!elf) {
            Regex("[\"']([^\"'\\n]+\\.(?:sqlite3|sqlite|db))[\"']").findAll(text).forEach { names += it.groupValues[1] }
            Regex("sqlite:/{2,4}([^\"'\\s]+)").findAll(text).forEach { names += it.groupValues[1] }
        }
        val created = ArrayList<String>()
        val root = base.canonicalPath + "/"
        for (n in names) {
            if (n == ":memory:" || n.contains("{") || n.contains("%") || n.contains("$") || n.contains("://")) continue
            val file = try { (if (n.startsWith("/")) File(n) else File(base, n)).canonicalFile } catch (e: Exception) { continue }
            if (!file.path.startsWith(root)) continue          // outside this server's folder: not allowed
            try {
                file.parentFile?.mkdirs()
                if (!file.exists() && file.createNewFile()) created += file.relativeTo(base).path
            } catch (e: Exception) {}
        }
        if (Store.dbFiles(this, id).isEmpty()) {
            try {
                val d = File(Store.dataDir(this, id), "data.sqlite3")
                if (d.createNewFile()) created += "data/data.sqlite3"
            } catch (e: Exception) {}
        }
        return created
    }

    private fun tail(f: File, n: Int): String = try {
        RandomAccessFile(f, "r").use {
            val len = minOf(n.toLong(), it.length()).toInt()
            it.seek(it.length() - len); val b = ByteArray(len); it.readFully(b); String(b)
        }
    } catch (e: Exception) { "" }

    // If the server logs "unable to open database file", create the DB/folders and restart it once.
    private fun watchDb(id: String, r: Run, f: File, elf: Boolean) {
        Thread {
            while (!r.stopped) {
                try { Thread.sleep(2000) } catch (e: Exception) { return@Thread }
                if (tail(Store.logFile(this, id), 8192).contains("unable to open database file", true)) {
                    main.post { onDbError(id, r, f, elf) }
                    return@Thread
                }
            }
        }.start()
    }

    private fun onDbError(id: String, r: Run, f: File, elf: Boolean) {
        if (runs[id] !== r || !fixedOnce.add(id)) return
        val made = prepareDb(id, f, elf)
        if (made.isEmpty()) {
            log(id, "[auto] เปิดฐานข้อมูลไม่ได้ และแก้อัตโนมัติไม่ได้ — ใช้ path แบบ relative หรือ os.environ[\"DB_PATH\"] (Go: os.Getenv)")
            return
        }
        killRun(id)
        startOne(id, "[auto] เปิดฐานข้อมูลไม่ได้ → สร้างให้แล้ว (${made.joinToString(", ")}) และรีสตาร์ต server")
    }

    private fun killRun(id: String) {
        val r = runs.remove(id) ?: return
        r.stopped = true
        try { r.tunnel?.destroy() } catch (e: Exception) {}
        try { r.server?.destroy() } catch (e: Exception) {}
        if (r.slot >= 0) {
            conns.remove(r.slot)?.let { try { unbindService(it) } catch (e: Exception) {} }
            stopService(Intent(this, workers[r.slot]))
        }
    }

    private fun stopOne(id: String) {
        killRun(id)
        Store.setRunning(this, Store.running(this) - id)
    }

    // ELF magic = compiled binary (e.g. Go linux/arm64), anything else = Python
    private fun isElf(f: File): Boolean = try {
        f.inputStream().use {
            val b = ByteArray(4); it.read(b)
            b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
        }
    } catch (e: Exception) { false }

    private fun runBinary(id: String, r: Run, f: File, port: Int) {
        try {
            f.setExecutable(true)
            val dir = Store.dir(this, id)
            val pb = ProcessBuilder(f.path)
            pb.directory(dir)
            pb.environment()["PORT"] = port.toString()
            pb.environment()["HOME"] = dir.path
            val data = File(dir, "data").apply { mkdirs() }
            pb.environment()["DATA_DIR"] = data.path
            pb.environment()["DB_PATH"] = File(data, "data.sqlite3").path
            pb.redirectErrorStream(true)
            val p = pb.start()
            r.server = p
            if (r.stopped) p.destroy()
            p.inputStream.bufferedReader().forEachLine { log(id, "[server] $it") }
            log(id, "[server] BINARY EXITED")
        } catch (e: Exception) { log(id, "[server] BINARY ERROR: " + e.message) }
    }

    private fun runTunnel(id: String, r: Run, port: Int, token: String) {
        try {
            Thread.sleep(2000)
            if (r.stopped) return
            val bin = File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
            val args = if (token.isNotEmpty())
                listOf(bin.path, "tunnel", "--no-autoupdate", "run", "--token", token)
            else listOf(bin.path, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:$port")
            val pb = ProcessBuilder(args)
            pb.environment()["HOME"] = Store.dir(this, id).path
            pb.environment()["TMPDIR"] = cacheDir.path
            pb.redirectErrorStream(true)
            val p = pb.start()
            r.tunnel = p
            if (r.stopped) p.destroy()
            p.inputStream.bufferedReader().forEachLine { log(id, "[tunnel] $it") }
        } catch (e: Exception) { log(id, "[tunnel] TUNNEL ERROR: " + e.message) }
    }

    override fun onDestroy() {
        runs.keys.toList().forEach { killRun(it) }
        releaseLocks()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
