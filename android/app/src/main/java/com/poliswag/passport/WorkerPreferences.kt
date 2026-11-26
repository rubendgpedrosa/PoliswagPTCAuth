package com.poliswag.passport

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

class WorkerPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var helperUrl: String
        get() = prefs.getString(KEY_HELPER_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_HELPER_URL, value.trim()).apply()
        }

    var workerToken: String
        get() = prefs.getString(KEY_WORKER_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_WORKER_TOKEN, value.trim()).apply()
        }

    /** True while the user wants the worker running: set by Start, cleared by Stop. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_START, value).apply()
        }

    /** Proxies for PTC logins, one per line. Empty means a direct connection. */
    var proxies: String
        get() = prefs.getString(KEY_PROXIES, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PROXIES, value.trim()).apply()
        }

    /** Picks the next proxy in turn, or null when none is configured. */
    fun nextProxy(): ProxyEntry? {
        val list = Proxies.parseList(proxies).proxies
        if (list.isEmpty()) return null
        val index = prefs.getInt(KEY_PROXY_INDEX, 0)
        prefs.edit().putInt(KEY_PROXY_INDEX, (index + 1) % list.size).apply()
        return list[index % list.size]
    }

    /** Saved bot-check cookies per route, as JSON (see BotCheckCookies.loadJars). */
    var cookieJars: String
        get() = prefs.getString(KEY_COOKIE_JARS, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_COOKIE_JARS, value).apply()
        }

    /**
     * The route of the login that is running now, or empty. Still set at the next
     * login means the last one never finished (cancelled, or the app was killed).
     */
    var loginInProgressRoute: String
        get() = prefs.getString(KEY_LOGIN_IN_PROGRESS, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LOGIN_IN_PROGRESS, value).apply()
        }

    val workerId: String
        get() {
            val existing = prefs.getString(KEY_WORKER_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = generateWorkerId()
            prefs.edit().putString(KEY_WORKER_ID, generated).apply()
            return generated
        }

    companion object {
        private const val PREFS_NAME = "poliswag_passport_prefs"
        private const val KEY_HELPER_URL = "helper_url"
        private const val KEY_WORKER_TOKEN = "worker_token"
        private const val KEY_WORKER_ID = "worker_id"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_PROXIES = "proxies"
        private const val KEY_COOKIE_JARS = "cookie_jars"
        private const val KEY_LOGIN_IN_PROGRESS = "login_in_progress_route"
        private const val KEY_PROXY_INDEX = "proxy_index"
        private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val random = SecureRandom()

        private fun generateWorkerId(): String {
            val sb = StringBuilder("android-")
            repeat(12) {
                sb.append(ID_CHARS[random.nextInt(ID_CHARS.length)])
            }
            return sb.toString()
        }
    }
}
