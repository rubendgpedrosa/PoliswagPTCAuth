package com.poliswag.passport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Starts the worker again after the device boots or the app is updated, but only if
 * the user had it running (they pressed Start and never pressed Stop).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = WorkerPreferences(context)
        if (!prefs.autoStart || prefs.helperUrl.isBlank()) return
        val start = Intent(context, WorkerService::class.java).apply {
            this.action = WorkerService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(context, start)
        } catch (e: Exception) {
            // Some Android builds refuse background starts; the user opens the app instead.
            WorkerState.log("could not start after boot: ${e.message}")
        }
    }
}
