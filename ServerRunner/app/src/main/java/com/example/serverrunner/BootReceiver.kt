package com.example.serverrunner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Resumes every server that was running (and not stopped by the user) when the device went off.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Store.running(c).isEmpty() && !Store.dashboardEnabled(c)) return
        c.startForegroundService(Intent(c, ServerService::class.java))
    }
}
