package com.poliswag.passport

import android.content.Context

object PageScriptBuilder {
    @Volatile
    private var cachedScript: String? = null

    fun loadCombinedScript(context: Context): String {
        cachedScript?.let { return it }
        synchronized(this) {
            cachedScript?.let { return it }
            val assets = context.applicationContext.assets
            val pageLogic = assets.open("pageLogic.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val contentJs = assets.open("content_android.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val combined = "$pageLogic\n;\n$contentJs"
            cachedScript = combined
            return combined
        }
    }
}
