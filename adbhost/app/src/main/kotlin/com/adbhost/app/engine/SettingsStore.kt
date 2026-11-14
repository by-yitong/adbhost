package com.adbhost.app.engine

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var tlsMode: Boolean
        get() = prefs.getBoolean(KEY_TLS, true)
        set(value) = prefs.edit().putBoolean(KEY_TLS, value).apply()

    var rootMode: Boolean
        get() = prefs.getBoolean(KEY_ROOT, false)
        set(value) = prefs.edit().putBoolean(KEY_ROOT, value).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var autoAcceptDebug: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ACCEPT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ACCEPT, value).apply()

    companion object {
        const val DEFAULT_PORT = 5555
        private const val PREFS_NAME = "adbhost_settings"
        private const val KEY_PORT = "port"
        private const val KEY_TLS = "tls_mode"
        private const val KEY_ROOT = "root_mode"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_AUTO_ACCEPT = "auto_accept_debug"
    }
}
