package com.adbhost.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.adbhost.app.engine.SettingsStore

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsStore(context)
        if (!settings.autoStart) return
        runCatching {
            ContextCompat.startForegroundService(context, AdbForegroundService.startIntent(context))
        }.onFailure {
            Log.w(TAG, "auto start failed: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
