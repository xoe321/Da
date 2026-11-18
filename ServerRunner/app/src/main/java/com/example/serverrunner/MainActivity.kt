package com.example.serverrunner

import android.app.Activity
import android.app.AlertDialog
import android.database.sqlite.SQLiteDatabase
import android.provider.OpenableColumns
import android.content.Intent
import android.content.SharedPreferences
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MainActivity : Activity() {
    private class Card(
        val container: LinearLayout,
        val name: EditText, val port: EditText, val token: EditText,
        val file: TextView, val db: TextView, val status: TextView,
        val url: TextView, val copyDomain: Button, val log: TextView, val logScroll: ScrollView)

    private lateinit var prefs: SharedPreferences
    private lateinit var listBox: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var drawer: LinearLayout
    private lateinit var drawerList: LinearLayout
    private lateinit var drawerOverlay: View
    private val cards = HashMap<String, Card>()
    private val drawerStatuses = HashMap<String, TextView>()
    private val h = Handler(Looper.getMainLooper())
    private var pendingStart: String? = null
    private var pickFor: String? = null
    private var exportFile: File? = null
    private val backgroundColor = Color.rgb(11, 18, 32)
    private val surfaceColor = Color.rgb(20, 30, 48)
    private val inputColor = Color.rgb(29, 42, 63)
    private val accentColor = Color.rgb(45, 212, 191)
    private val successColor = Color.rgb(52, 211, 153)
    private val errorColor = Color.rgb(248, 113, 113)
    private val mutedColor = Color.rgb(148, 163, 184)
    private val textColor = Color.rgb(226, 232, 240)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = backgroundColor
            window.navigationBarColor = backgroundColor
            window.decorView.systemUiVisibility = 0
        }
        prefs = getSharedPreferences("p", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        migrateOld()

        val root = FrameLayout(this).apply { setBackgroundColor(backgroundColor) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Server Runner"
            textSize = 22f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolbar.addView(styledButton("☰", inputColor).apply {
            contentDescription = "เปิดรายชื่อ Server"
            setOnClickListener { openDrawer() }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val add = styledButton("+ เพิ่ม Server", accentColor).apply {
            setTextColor(backgroundColor)
            setOnClickListener { addServer() }
        }
        val stopAll = styledButton("หยุดทั้งหมด", inputColor).apply { setOnClickListener { stopAll() } }
        actions.addView(add, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        actions.addView(stopAll, LinearLayout.LayoutParams(0, dp(48), 1f))

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(listBox)
        }
        content.addView(toolbar)
        content.addView(actions, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        content.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        root.addView(content)

        drawerOverlay = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(drawerOverlay, FrameLayout.LayoutParams(-1, -1))

        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(surfaceColor, inputColor)
            setPadding(dp(16), dp(18), dp(16), dp(16))
            elevation = dp(16).toFloat()
            visibility = View.GONE
        }
        val drawerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        drawerHeader.addView(TextView(this).apply {
            text = "เซิร์ฟเวอร์ทั้งหมด"
            textSize = 19f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        drawerHeader.addView(styledButton("ปิด", inputColor).apply {
            setOnClickListener { closeDrawer() }
        }, LinearLayout.LayoutParams(dp(64), dp(42)))
        drawerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val drawerScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(drawerList)
        }
        drawer.addView(drawerHeader)
        drawer.addView(drawerScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        val drawerWidth = minOf(dp(340), resources.displayMetrics.widthPixels - dp(40))
        root.addView(drawer, FrameLayout.LayoutParams(drawerWidth, -1, Gravity.END))

        setContentView(root)
        buildList()
        if (intent?.getBooleanExtra("stop", false) == true) { stopAll(); intent.removeExtra("stop") }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: Int, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(16).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun styledButton(label: String, color: Int) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(textColor)
        backgroundTintList = null
        background = roundedBackground(color)
        minHeight = dp(42)
        stateListAnimator = null
    }

    private fun openDrawer() {
        refreshDrawer()
        drawerOverlay.visibility = View.VISIBLE
        drawer.visibility = View.VISIBLE
        drawer.translationX = drawer.width.toFloat()
        drawer.animate().translationX(0f).setDuration(180).start()
    }

    private fun closeDrawer() {
        drawerOverlay.visibility = View.GONE
        drawer.visibility = View.GONE
    }

    // Older single-server version stored one "server_file" + a token pref.
    private fun migrateOld() {
        val old = File(filesDir, "server_file")
        if (!old.exists() || Store.load(this).isNotEmpty()) return

        val s = Srv("s1", "Server 1", 8080, prefs.getString("token", "") ?: "")
        val serverDir = Store.dir(this, s.id)
        old.copyTo(File(serverDir, "server_file"), true)
        migrateDatabases(File(filesDir, "data"), Store.dataDir(this, s.id))
        migrateDatabases(filesDir, serverDir)
        Store.save(this, listOf(s))
    }

    private fun migrateDatabases(source: File, destination: File) {
        if (!source.exists()) return
        val root = source.canonicalFile
        val destinationRoot = destination.canonicalFile
        val destinationPrefix = destinationRoot.path + File.separator
        source.walkTopDown()
            .onEnter { directory ->
                val path = directory.canonicalPath
                directory.canonicalFile != destinationRoot && !path.startsWith(destinationPrefix)
            }
            .filter { it.isFile && isDatabaseFile(it.name) }
            .forEach { file ->
                val target = File(destination, file.relativeTo(root).path)
                target.parentFile?.mkdirs()
                if (!target.exists()) file.copyTo(target)
            }
    }

    private fun isDatabaseFile(name: String): Boolean {
        val base = name.lowercase()
            .removeSuffix("-wal")
            .removeSuffix("-shm")
            .removeSuffix("-journal")
        return base.endsWith(".sqlite3") || base.endsWith(".sqlite") || base.endsWith(".db")
    }

    private fun buildList() {
        listBox.removeAllViews(); cards.clear()
        Store.load(this).forEach { addCard(it) }
        refreshDrawer()
    }

    private fun addServer() {
        val l = Store.load(this)
        val s = Srv("s" + System.currentTimeMillis(), "Server ${l.size + 1}",
            (l.maxOfOrNull { it.port } ?: 8079) + 1, "")
        l.add(s); Store.save(this, l)
        addCard(s)
        refreshDrawer()
    }

    private fun addCard(s: Srv) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBackground(surfaceColor, inputColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = EditText(this).apply {
            setText(s.name)
            hint = "ชื่อ Server"
            setSingleLine()
            styleInput(this)
        }
        header.addView(name, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        val status = TextView(this).apply {
            text = "หยุดอยู่"
            textSize = 12f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundedBackground(inputColor)
        }
        header.addView(status)

        val file = TextView(this).apply {
            text = fileText(s.id)
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        val pick = Button(this).apply {
            text = "เลือกไฟล์ Server (.py หรือ Go)"
            setOnClickListener {
                pickFor = s.id
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, 1)
            }
        }
        styleButton(pick, inputColor)

        val db = TextView(this).apply {
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(dp(2), dp(8), dp(2), dp(4))
        }
        val dbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dbIn = styledButton("นำเข้า SQLite", inputColor).apply { setOnClickListener { importDb(s.id) } }
        val dbOut = styledButton("สำรอง SQLite", inputColor).apply { setOnClickListener { exportDb(s.id) } }
        listOf(dbIn, dbOut).forEach {
            dbRow.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        }
        val port = EditText(this).apply {
            setText(s.port.toString())
            hint = "Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            styleInput(this)
        }
        val token = EditText(this).apply {
            hint = "Cloudflare Tunnel token (เว้นว่างเพื่อใช้ลิงก์สุ่ม)"
            setSingleLine()
            setText(s.token)
            styleInput(this)
        }
        val url = TextView(this).apply {
            textSize = 14f
            setTextColor(accentColor)
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        val copyDomain = styledButton("คัดลอก Domain", Color.rgb(13, 148, 136)).apply {
            visibility = View.GONE
            setOnClickListener {
                val domain = url.text.toString()
                if (domain.isNotBlank()) {
                    (this@MainActivity.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Server domain", domain))
                    Toast.makeText(this@MainActivity, "คัดลอก Domain แล้ว", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val logHeader = TextView(this).apply {
            text = "Logs  ·  ลากขึ้นลงเพื่อดู"
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(dp(2), dp(10), dp(2), dp(6))
        }
        val log = TextView(this).apply {
            text = "รอ Log จาก Server..."
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(mutedColor)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val logScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = true
            isNestedScrollingEnabled = true
            background = roundedBackground(Color.rgb(8, 13, 24))
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            addView(log, FrameLayout.LayoutParams(-1, -2))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = styledButton("เริ่ม", Color.rgb(5, 150, 105)).apply { setOnClickListener { startFlow(s.id) } }
        val stop = styledButton("หยุด", inputColor).apply { setOnClickListener { stopOne(s.id) } }
        val del = styledButton("ลบ", Color.rgb(127, 29, 29)).apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("ลบ ${s.name}?")
                    .setMessage("ไฟล์ Server และฐานข้อมูลของ Server นี้จะถูกลบ")
                    .setPositiveButton("ลบ") { _, _ -> deleteServer(s.id) }
                    .setNegativeButton("ยกเลิก", null).show()
            }
        }
        listOf(start, stop, del).forEach {
            row.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        }

        box.addView(header)
        box.addView(pick, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        box.addView(file)
        box.addView(port, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        box.addView(token, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        box.addView(db)
        box.addView(dbRow)
        box.addView(row, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        box.addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        box.addView(copyDomain, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })
        box.addView(logHeader)
        box.addView(logScroll, LinearLayout.LayoutParams(-1, dp(190)))
        listBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        cards[s.id] = Card(box, name, port, token, file, db, status, url, copyDomain, log, logScroll)
    }

    private fun styleInput(input: EditText) {
        input.textSize = 15f
        input.setTextColor(textColor)
        input.setHintTextColor(mutedColor)
        input.background = roundedBackground(inputColor)
        input.setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun styleButton(button: Button, color: Int) {
        button.textSize = 14f
        button.isAllCaps = false
        button.setTextColor(textColor)
        button.backgroundTintList = null
        button.background = roundedBackground(color)
        button.minHeight = dp(42)
        button.stateListAnimator = null
    }

    private fun refreshDrawer() {
        drawerList.removeAllViews()
        drawerStatuses.clear()
        val running = Store.running(this)
        val servers = Store.load(this)
        servers.forEach { server ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground(inputColor)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    closeDrawer()
                    cards[server.id]?.let { card ->
                        listScroll.post { listScroll.smoothScrollTo(0, card.container.top) }
                    }
                }
            }
            val title = TextView(this).apply {
                text = server.name.ifBlank { "Server" }
                textSize = 15f
                setTextColor(textColor)
                typeface = Typeface.DEFAULT_BOLD
            }
            val detail = TextView(this).apply {
                text = "Port ${server.port}"
                textSize = 12f
                setTextColor(mutedColor)
            }
            val state = TextView(this).apply { textSize = 12f }
            row.addView(title)
            row.addView(detail)
            row.addView(state, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            drawerList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            drawerStatuses[server.id] = state
            updateStatus(server.id, state, running)
        }
        if (servers.isEmpty()) {
            drawerList.addView(TextView(this).apply {
                text = "ยังไม่มี Server แตะ + เพิ่ม Server เพื่อเริ่มต้น"
                textSize = 14f
                setTextColor(mutedColor)
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
        }
    }

    private fun updateStatus(id: String, view: TextView, running: Set<String>, logText: String = readLog(Store.logFile(this, id))) {
        val lines = logText.lineSequence().toList()
        val serverErrorIndex = lines.indexOfLast {
            !it.startsWith("[tunnel] ") && errorLine.containsMatchIn(it)
        }
        val serverSuccessIndex = lines.indexOfLast {
            !it.startsWith("[tunnel] ") && serverSuccessLine.containsMatchIn(it)
        }
        val tunnelErrorIndex = lines.indexOfLast {
            it.startsWith("[tunnel] ") && errorLine.containsMatchIn(it)
        }
        val tunnelSuccessIndex = lines.indexOfLast {
            it.startsWith("[tunnel] ") && tunnelSuccessLine.containsMatchIn(it)
        }
        val (label, color) = when {
            id !in running -> "หยุดอยู่" to mutedColor
            serverErrorIndex > serverSuccessIndex -> "Server Error" to errorColor
            tunnelErrorIndex > tunnelSuccessIndex -> "Tunnel Error" to errorColor
            serverSuccessIndex >= 0 -> "Server ทำงานปกติ" to successColor
            tunnelSuccessIndex >= 0 -> "Tunnel เชื่อมต่อแล้ว" to successColor
            else -> "กำลังเริ่ม" to Color.rgb(251, 191, 36)
        }
        view.text = "● $label"
        view.setTextColor(color)
        view.background = roundedBackground(Color.argb(24, Color.red(color), Color.green(color), Color.blue(color)))
        if (view === cards[id]?.status) view.setPadding(dp(10), dp(7), dp(10), dp(7))
    }

    private fun renderLog(view: TextView, content: String) {
        val styled = SpannableStringBuilder()
        var offset = 0
        content.lineSequence().forEach { line ->
            val color = when {
                errorLine.containsMatchIn(line) -> errorColor
                successLine.containsMatchIn(line) -> successColor
                else -> mutedColor
            }
            val start = offset
            styled.append(line).append('\n')
            offset = styled.length
            if (offset > start) styled.setSpan(
                ForegroundColorSpan(color), start, offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        view.text = styled
    }

    private fun readLog(file: File): String = try {
        RandomAccessFile(file, "r").use { input ->
            val length = minOf(input.length(), 24L * 1024).toInt()
            val bytes = ByteArray(length)
            input.seek(input.length() - length)
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8).takeLast(12000)
        }
    } catch (e: Exception) {
        ""
    }

    private val errorLine = Regex("(?i)(\\b(error|err|fatal|exception|traceback|failed|refused|denied)\\b|[A-Za-z]+Error\\b)")
    private val successLine = Regex(
        "(?i)(registered tunnel connection|quick tunnel has been created|application startup complete|uvicorn running on|server is running|listening on|ready to accept)"
    )
    private val serverSuccessLine = Regex(
        "(?i)(application startup complete|uvicorn running on|server is running|listening on|ready to accept)"
    )
    private val tunnelSuccessLine = Regex("(?i)(registered tunnel connection|quick tunnel has been created)")
    private val domainPattern = Regex("https://(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com")

    private fun fileText(id: String) =
        if (File(Store.dir(this, id), "server_file").exists()) "ไฟล์ server พร้อมแล้ว" else "ยังไม่ได้เลือกไฟล์"

    private fun dbText(id: String): String {
        val l = Store.dbFiles(this, id)
        return if (l.isEmpty()) "ฐานข้อมูล: ยังไม่มี (server เก็บที่ \$DB_PATH หรือโฟลเดอร์ data/)"
        else "ฐานข้อมูล: " + l.joinToString(", ") {
            "${it.relativeTo(Store.dir(this, id)).path} (${it.length() / 1024} KB)"
        }
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
        try {
            Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            Toast.makeText(this, "นำเข้าฐานข้อมูลไม่ได้: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        File(dst.path + "-wal").delete()
        File(dst.path + "-shm").delete()
        cards[id]?.db?.text = dbText(id)
        Toast.makeText(this, "นำเข้า $name แล้ว", Toast.LENGTH_SHORT).show()
    }

    private fun exportDb(id: String) {
        if (id in Store.running(this)) {
            Toast.makeText(this, "กด Stop ก่อนสำรองฐานข้อมูล", Toast.LENGTH_SHORT).show()
            return
        }
        val l = Store.dbFiles(this, id)
        if (l.isEmpty()) { Toast.makeText(this, "ยังไม่มีฐานข้อมูล", Toast.LENGTH_SHORT).show(); return }
        val go: (File) -> Unit = go@ { f ->
            // Flush the WAL into the main file so the single exported file is complete.
            try {
                SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                        if (!c.moveToFirst() || c.getInt(0) != 0) {
                            throw IllegalStateException("SQLite WAL checkpoint is busy")
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "สำรองฐานข้อมูลไม่ได้: ${e.message}", Toast.LENGTH_LONG).show()
                return@go
            }
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
        refreshDrawer()
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
        cards[id]?.url?.visibility = View.GONE
        cards[id]?.copyDomain?.visibility = View.GONE
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

    override fun onBackPressed() {
        if (::drawer.isInitialized && drawer.visibility == View.VISIBLE) {
            closeDrawer()
            return
        }
        super.onBackPressed()
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
                val t = readLog(Store.logFile(this@MainActivity, id))
                val oldY = c.logScroll.scrollY
                val wasAtBottom = c.log.height <= c.logScroll.height ||
                    oldY + c.logScroll.height >= c.log.height - dp(24)
                val visibleLog = if (t.isBlank()) "รอ Log จาก Server..." else t
                if (c.log.text.toString().trimEnd('\n') != visibleLog.trimEnd('\n')) {
                    renderLog(c.log, visibleLog)
                    c.logScroll.post {
                        if (wasAtBottom) c.logScroll.fullScroll(View.FOCUS_DOWN)
                        else c.logScroll.scrollTo(0, minOf(oldY, maxOf(0, c.log.height - c.logScroll.height)))
                    }
                }
                c.db.text = dbText(id)
                updateStatus(id, c.status, running, t)
                drawerStatuses[id]?.let { updateStatus(id, it, running, t) }
                domainPattern.find(t)?.value?.let { domain ->
                    if (c.url.text.toString() != domain) c.url.text = domain
                    c.url.visibility = View.VISIBLE
                    c.copyDomain.visibility = View.VISIBLE
                }
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
