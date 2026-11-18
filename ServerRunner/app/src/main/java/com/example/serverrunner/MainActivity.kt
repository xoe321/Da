package com.example.serverrunner

import android.app.Activity
import android.app.AlertDialog
import android.database.sqlite.SQLiteDatabase
import android.provider.OpenableColumns
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private class Card(
        val name: EditText, val port: EditText, val token: EditText,
        val file: TextView, val db: TextView, val status: TextView, val url: TextView, val log: TextView)

    private lateinit var prefs: SharedPreferences
    private lateinit var listBox: LinearLayout
    private val cards = HashMap<String, Card>()
    private val h = Handler(Looper.getMainLooper())
    private var pendingStart: String? = null
    private var pickFor: String? = null
    private var exportFile: File? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("p", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        migrateOld()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 80, 32, 32) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val add = Button(this).apply { text = "+ เพิ่ม Server"; setOnClickListener { addServer() } }
        val stopAll = Button(this).apply { text = "Stop ทั้งหมด"; setOnClickListener { stopAll() } }
        bar.addView(add, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(stopAll, LinearLayout.LayoutParams(0, -2, 1f))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bar)
        root.addView(ScrollView(this).apply { addView(listBox) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        buildList()
        if (intent?.getBooleanExtra("stop", false) == true) { stopAll(); intent.removeExtra("stop") }
    }

    // Older single-server version stored one "server_file" + a token pref.
    private fun migrateOld() {
        val old = File(filesDir, "server_file")
        if (old.exists() && Store.load(this).isEmpty()) {
            val s = Srv("s1", "Server 1", 8080, prefs.getString("token", "") ?: "")
            old.copyTo(File(Store.dir(this, s.id), "server_file"), true)
            old.delete()
            Store.save(this, listOf(s))
        }
    }

    private fun buildList() {
        listBox.removeAllViews(); cards.clear()
        Store.load(this).forEach { addCard(it) }
    }

    private fun addServer() {
        val l = Store.load(this)
        val s = Srv("s" + System.currentTimeMillis(), "Server ${l.size + 1}",
            (l.maxOfOrNull { it.port } ?: 8079) + 1, "")
        l.add(s); Store.save(this, l)
        addCard(s)
    }

    private fun addCard(s: Srv) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(0x14000000)
        }
        val name = EditText(this).apply { setText(s.name); hint = "ชื่อ"; setSingleLine() }
        val file = TextView(this).apply { text = fileText(s.id) }
        val pick = Button(this).apply {
            text = "เลือกไฟล์ (.py หรือ Go binary)"
            setOnClickListener {
                pickFor = s.id
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, 1)
            }
        }
        val db = TextView(this).apply { textSize = 12f }
        val dbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dbIn = Button(this).apply { text = "นำเข้า .sqlite3"; setOnClickListener { importDb(s.id) } }
        val dbOut = Button(this).apply { text = "ส่งออก/สำรอง"; setOnClickListener { exportDb(s.id) } }
        listOf(dbIn, dbOut).forEach { dbRow.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        val port = EditText(this).apply {
            setText(s.port.toString()); hint = "Port"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        val token = EditText(this).apply {
            hint = "Cloudflare Tunnel token (ว่าง = ลิงก์สุ่ม)"; setSingleLine(); setText(s.token)
        }
        val status = TextView(this).apply { textSize = 15f }
        val url = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        val log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 10f; maxLines = 8 }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(this).apply { text = "Start"; setOnClickListener { startFlow(s.id) } }
        val stop = Button(this).apply { text = "Stop"; setOnClickListener { stopOne(s.id) } }
        val del = Button(this).apply { text = "ลบ"; setOnClickListener {
            AlertDialog.Builder(this@MainActivity).setTitle("ลบ ${s.name}?")
                .setMessage("ไฟล์ server และฐานข้อมูล (.sqlite3) ทั้งหมดของตัวนี้จะถูกลบ")
                .setPositiveButton("ลบ") { _, _ -> deleteServer(s.id) }
                .setNegativeButton("ยกเลิก", null).show()
        } }
        listOf(start, stop, del).forEach { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        listOf(name, pick, file, port, token, db, dbRow, row, status, url, log).forEach { box.addView(it) }
        listBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 })
        cards[s.id] = Card(name, port, token, file, db, status, url, log)
    }

    private fun fileText(id: String) =
        if (File(Store.dir(this, id), "server_file").exists()) "ไฟล์ server พร้อมแล้ว" else "ยังไม่ได้เลือกไฟล์"

    private fun dbText(id: String): String {
        val l = Store.dbFiles(this, id)
        return if (l.isEmpty()) "ฐานข้อมูล: ยังไม่มี (server เก็บที่ \$DB_PATH หรือโฟลเดอร์ data/)"
        else "ฐานข้อมูล: " + l.joinToString(", ") { "${it.name} (${it.length() / 1024} KB)" }
    }

    private fun importDb(id: String) {
        if (id in Store.running(this)) {
            Toast.makeText(this, "กด Stop ก่อนนำเข้าฐานข้อมูล", Toast.LENGTH_SHORT).show(); return
        }
        pickFor = id
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, 5)
    }

    private fun doImport(id: String, uri: Uri) {
        var name = "data.sqlite3"
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getColumnIndex(OpenableColumns.DISPLAY_NAME).let { ix ->
                if (ix >= 0) name = it.getString(ix) }
        }
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!name.lowercase().let { it.endsWith(".sqlite3") || it.endsWith(".sqlite") || it.endsWith(".db") })
            name += ".sqlite3"
        val tmp = File(Store.dataDir(this, id), "$name.importing")
        contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        val head = try { tmp.inputStream().use { ByteArray(16).also { b -> it.read(b) } } } catch (e: Exception) { ByteArray(0) }
        if (String(head, Charsets.ISO_8859_1) != "SQLite format 3\u0000") {
            tmp.delete()
            Toast.makeText(this, "ไม่ใช่ไฟล์ SQLite", Toast.LENGTH_LONG).show(); return
        }
        val dst = File(Store.dataDir(this, id), name)
        File(dst.path + "-wal").delete(); File(dst.path + "-shm").delete()   // stale journal would corrupt the new file
        dst.delete(); tmp.renameTo(dst)
        cards[id]?.db?.text = dbText(id)
        Toast.makeText(this, "นำเข้า $name แล้ว", Toast.LENGTH_SHORT).show()
    }

    private fun exportDb(id: String) {
        val l = Store.dbFiles(this, id)
        if (l.isEmpty()) { Toast.makeText(this, "ยังไม่มีฐานข้อมูล", Toast.LENGTH_SHORT).show(); return }
        val go = { f: File ->
            // Flush the WAL into the main file so the single exported file is complete.
            try {
                SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c -> c.moveToFirst() }
                }
            } catch (e: Exception) {}
            exportFile = f
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"; putExtra(Intent.EXTRA_TITLE, f.name)
            }, 6)
        }
        if (l.size == 1) go(l[0])
        else AlertDialog.Builder(this).setTitle("เลือกไฟล์ที่จะส่งออก")
            .setItems(l.map { it.name }.toTypedArray()) { _, i -> go(l[i]) }.show()
    }

    private fun saveCard(id: String) {
        val c = cards[id] ?: return
        val l = Store.load(this)
        l.firstOrNull { it.id == id }?.let {
            it.name = c.name.text.toString().ifBlank { "Server" }
            it.port = c.port.text.toString().toIntOrNull() ?: 8080
            it.token = c.token.text.toString().trim()
        }
        Store.save(this, l)
    }

    private fun deleteServer(id: String) {
        stopOne(id)
        Store.dir(this, id).deleteRecursively()
        Store.save(this, Store.load(this).filter { it.id != id })
        buildList()
    }

    // Ask for each permission screen once, then launch. Each result re-enters startFlow().
    private fun ask(flag: String, intent: Intent, code: Int): Boolean {
        if (prefs.getBoolean(flag, false)) return false
        prefs.edit().putBoolean(flag, true).apply()
        return try { startActivityForResult(intent, code); true } catch (e: Exception) { false }
    }

    private fun startFlow(id: String) {
        pendingStart = id
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName) &&
            ask("askedBatt", Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")), 2)) return
        if (!Settings.System.canWrite(this) &&
            ask("askedWrite", Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName")), 3)) return
        if (ask("askedSaver", Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), 4)) return
        pendingStart = null
        launch(id)
    }

    private fun launch(id: String) {
        saveCard(id)
        if (!File(Store.dir(this, id), "server_file").exists()) {
            Toast.makeText(this, "ยังไม่ได้เลือกไฟล์ server", Toast.LENGTH_SHORT).show(); return
        }
        val all = Store.load(this)
        val me = all.firstOrNull { it.id == id } ?: return
        val clash = all.firstOrNull { it.id != id && it.id in Store.running(this) && it.port == me.port }
        if (clash != null) {
            Toast.makeText(this, "Port ${me.port} ซ้ำกับ ${clash.name}", Toast.LENGTH_LONG).show(); return
        }
        cards[id]?.url?.text = ""
        startForegroundService(Intent(this, ServerService::class.java)
            .setAction(ServerService.START).putExtra("id", id))
        dim(true)
    }

    private fun stopOne(id: String) {
        startService(Intent(this, ServerService::class.java)
            .setAction(ServerService.STOP).putExtra("id", id))
        if ((Store.running(this) - id).isEmpty()) dim(false)
    }

    private fun stopAll() {
        startService(Intent(this, ServerService::class.java).setAction(ServerService.STOP_ALL))
        dim(false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("stop", false) == true) { stopAll(); intent.removeExtra("stop") }
    }

    // Lowest brightness: this window always; whole system too if "modify system settings" was granted.
    private fun dim(on: Boolean) {
        prefs.edit().putBoolean("dimmed", on).apply()
        window.attributes = window.attributes.apply { screenBrightness = if (on) 0.01f else -1f }
        if (!Settings.System.canWrite(this)) return
        val cr = contentResolver
        if (on) {
            if (!prefs.contains("oldB")) prefs.edit()
                .putInt("oldB", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
                .putInt("oldM", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)).apply()
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 1)
        } else if (prefs.contains("oldB")) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, prefs.getInt("oldM", 0))
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, prefs.getInt("oldB", 128))
            prefs.edit().remove("oldB").remove("oldM").apply()
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        when (req) {
            1 -> {
                val id = pickFor ?: return
                val uri = data?.data ?: return
                contentResolver.openInputStream(uri)?.use { i ->
                    File(Store.dir(this, id), "server_file").outputStream().use { o -> i.copyTo(o) }
                }
                cards[id]?.file?.text = fileText(id)
            }
            5 -> { val id = pickFor ?: return; doImport(id, data?.data ?: return) }
            6 -> {
                val f = exportFile ?: return
                val uri = data?.data ?: return
                contentResolver.openOutputStream(uri, "wt")?.use { o -> f.inputStream().use { it.copyTo(o) } }
                Toast.makeText(this, "ส่งออกแล้ว", Toast.LENGTH_SHORT).show()
            }
            2, 3, 4 -> pendingStart?.let { startFlow(it) }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val running = Store.running(this@MainActivity)
            for ((id, c) in cards) {
                val t = try { Store.logFile(this@MainActivity, id).readText() } catch (e: Exception) { "" }
                c.log.text = t.takeLast(1500)
                c.db.text = dbText(id)
                c.status.text = if (id in running) "● ทำงานอยู่" else "○ หยุด"
                Regex("https://(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com").find(t)?.let { c.url.text = it.value }
            }
            h.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("dimmed", false)) {
            if (Store.running(this).isEmpty()) dim(false)
            else window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        }
        h.post(poll)
    }
    override fun onPause() { super.onPause(); h.removeCallbacks(poll) }
}
