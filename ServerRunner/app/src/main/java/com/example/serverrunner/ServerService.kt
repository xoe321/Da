package com.example.serverrunner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class ServerService : Service() {
    private var tunnel: Process? = null
    private var server: Process? = null

    private fun log(s: String) = File(filesDir, "log.txt").appendText(s + "\n")

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val ch = "srv"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Server", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, ch)
            .setContentTitle("Server is running")
            .setSmallIcon(android.R.drawable.stat_sys_upload).build())
        val port = i?.getIntExtra("port", 8080) ?: 8080
        val token = i?.getStringExtra("token") ?: ""
        File(filesDir, "log.txt").writeText("")
        Thread { runServer(port) }.start()
        Thread { runTunnel(port, token) }.start()
        return START_NOT_STICKY
    }

    // ELF magic = compiled binary (e.g. Go linux/arm64), anything else = Python
    private fun isElf(f: File): Boolean = try {
        f.inputStream().use {
            val b = ByteArray(4); it.read(b)
            b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
        }
    } catch (e: Exception) { false }

    private fun runServer(port: Int) {
        val f = File(filesDir, "server_file")
        if (!f.exists()) { log("ยังไม่ได้เลือกไฟล์ server"); return }
        if (isElf(f)) runBinary(f, port) else runPython(f)
    }

    private fun runBinary(f: File, port: Int) {
        try {
            f.setExecutable(true)
            val pb = ProcessBuilder(f.path)
            pb.directory(filesDir)
            pb.environment()["PORT"] = port.toString()
            pb.environment()["HOME"] = filesDir.path
            pb.redirectErrorStream(true)
            server = pb.start()
            server!!.inputStream.bufferedReader().forEachLine { log(it) }
            log("BINARY EXITED")
        } catch (e: Exception) { log("BINARY ERROR: " + e.message) }
    }

    private fun runPython(f: File) {
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            Python.getInstance().getModule("runner")
                .callAttr("run", f.path, File(filesDir, "log.txt").path)
        } catch (e: Exception) { log("PY ERROR: " + e.message) }
    }

    private fun runTunnel(port: Int, token: String) {
        try {
            Thread.sleep(2000)
            val bin = File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
            val args = if (token.isNotEmpty())
                listOf(bin.path, "tunnel", "--no-autoupdate", "run", "--token", token)
            else listOf(bin.path, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:$port")
            val pb = ProcessBuilder(args)
            pb.environment()["HOME"] = filesDir.path
            pb.environment()["TMPDIR"] = cacheDir.path
            pb.redirectErrorStream(true)
            tunnel = pb.start()
            tunnel!!.inputStream.bufferedReader().forEachLine { log(it) }
        } catch (e: Exception) { log("TUNNEL ERROR: " + e.message) }
    }

    override fun onDestroy() {
        tunnel?.destroy()
        server?.destroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
