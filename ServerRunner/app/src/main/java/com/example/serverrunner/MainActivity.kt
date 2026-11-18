package com.example.serverrunner

import android.app.Activity
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
    private lateinit var logView: TextView
    private lateinit var urlView: TextView
    private lateinit var fileView: TextView
    private lateinit var portView: EditText
    private lateinit var tokenView: EditText
    private lateinit var prefs: SharedPreferences
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("p", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 80, 32, 32) }
        fileView = TextView(this).apply {
            text = if (File(filesDir, "server_file").exists()) "ไฟล์ server พร้อมแล้ว" else "ยังไม่ได้เลือกไฟล์"
        }
        val pick = Button(this).apply {
            text = "เลือกไฟล์ (.py หรือ Go binary)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, 1)
            }
        }
        portView = EditText(this).apply {
            setText("8080"); hint = "Port"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        tokenView = EditText(this).apply {
            hint = "Cloudflare Tunnel token (ว่าง = ลิงก์สุ่ม)"; setSingleLine()
            setText(prefs.getString("token", ""))
        }
        urlView = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f }
        val start = Button(this).apply { text = "Start"; setOnClickListener { startFlow() } }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ServerService::class.java))
                dim(false)
            }
        }
        listOf(pick, fileView, portView, tokenView, start, stop, urlView).forEach { root.addView(it) }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    // Ask for each permission screen once, then launch. Each result re-enters startFlow().
    private fun ask(flag: String, intent: Intent, code: Int): Boolean {
        if (prefs.getBoolean(flag, false)) return false
        prefs.edit().putBoolean(flag, true).apply()
        return try { startActivityForResult(intent, code); true } catch (e: Exception) { false }
    }

    private fun startFlow() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName) &&
            ask("askedBatt", Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")), 2)) return
        if (!Settings.System.canWrite(this) &&
            ask("askedWrite", Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName")), 3)) return
        if (ask("askedSaver", Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), 4)) return
        launch()
    }

    private fun launch() {
        urlView.text = ""
        prefs.edit().putString("token", tokenView.text.toString().trim()).apply()
        startForegroundService(Intent(this, ServerService::class.java)
            .putExtra("port", portView.text.toString().toIntOrNull() ?: 8080)
            .putExtra("token", tokenView.text.toString().trim()))
        dim(true)
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
                val uri = data?.data ?: return
                contentResolver.openInputStream(uri)?.use { i ->
                    File(filesDir, "server_file").outputStream().use { o -> i.copyTo(o) }
                }
                fileView.text = "ไฟล์ server พร้อมแล้ว"
            }
            2, 3, 4 -> startFlow()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val t = try { File(filesDir, "log.txt").readText() } catch (e: Exception) { "" }
            logView.text = t.takeLast(4000)
            Regex("https://(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com").find(t)?.let { urlView.text = it.value }
            h.postDelayed(this, 1000)
        }
    }
    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("dimmed", false))
            window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        h.post(poll)
    }
    override fun onPause() { super.onPause(); h.removeCallbacks(poll) }
}
