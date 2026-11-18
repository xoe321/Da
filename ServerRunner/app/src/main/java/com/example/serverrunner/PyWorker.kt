package com.example.serverrunner

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

// One Python interpreter per Android process, so every Python server gets its own process
// (own cwd, own stdout, own crash domain). ServerService starts/binds/stops these.
open class PyWorker : Service() {
    override fun onBind(i: Intent?): IBinder? = Binder()

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null) return START_NOT_STICKY
        val path = i.getStringExtra("path") ?: return START_NOT_STICKY
        val log = i.getStringExtra("log") ?: return START_NOT_STICKY
        val port = i.getIntExtra("port", 8080)
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                Python.getInstance().getModule("runner").callAttr("run", path, log, port)
            } catch (e: Exception) { File(log).appendText("PY ERROR: ${e.message}\n") }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() { android.os.Process.killProcess(android.os.Process.myPid()) }
}

class PyWorker0 : PyWorker()
class PyWorker1 : PyWorker()
class PyWorker2 : PyWorker()
class PyWorker3 : PyWorker()
class PyWorker4 : PyWorker()
class PyWorker5 : PyWorker()
