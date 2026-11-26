package com.poliswag.passport

/**
 * Picks the cookies that survive between logins.
 *
 * Every login must start with no PTC session, or the next account could be signed in
 * as the previous one. But the bot check on the login site (Imperva) also stores
 * cookies on that same site, and wiping those makes each login look like a first-time
 * visitor. So: wipe everything, then put back only the cookies whose names are known
 * to belong to the bot check. A cookie we do not recognise is never kept.
 *
 * The kept cookies are saved per route (per proxy, or direct), and only after a login
 * that got PTC's own answer. After an error, a timeout, a cancel or a killed app, that
 * route's cookies are thrown away, because they may carry a "blocked" mark.
 *
 * Measured on a phone: about 5 s per login with the cookies, about 15 s without.
 */
object BotCheckCookies {
    private val KEEP = listOf(
        Regex("^visid_incap_.+"),   // Imperva: long-lived visitor ID
        Regex("^incap_ses_.+"),     // Imperva: session
        Regex("^nlbi_.+"),          // Imperva: load balancer
        Regex("^reese84$"),         // Imperva: advanced bot protection token
        Regex("^___utmvc$")         // Imperva: challenge answer
    )

    /**
     * Names the way a login goes out: the proxy's address, or "direct". Never
     * credentials. Each route gets its own saved set of bot-check cookies (a "jar"),
     * so to the site every proxy looks like its own returning visitor, and a block
     * picked up on one proxy stays with that proxy.
     */
    fun routeOf(proxy: ProxyEntry?): String = proxy?.rule ?: DIRECT

    /** Reads the saved jars: route -> cookies. Bad or empty text gives no jars. */
    fun loadJars(json: String?): MutableMap<String, List<Pair<String, String>>> {
        val jars = mutableMapOf<String, List<Pair<String, String>>>()
        if (json.isNullOrBlank()) return jars
        try {
            val root = org.json.JSONObject(json)
            for (route in root.keys()) {
                val saved = root.getJSONObject(route)
                val cookies = saved.keys().asSequence()
                    .filter { isKept(it) }
                    .map { it to saved.getString(it) }
                    .toList()
                if (cookies.isNotEmpty()) jars[route] = cookies
            }
        } catch (_: org.json.JSONException) {
            jars.clear()
        }
        return jars
    }

    fun dumpJars(jars: Map<String, List<Pair<String, String>>>): String {
        val root = org.json.JSONObject()
        for ((route, cookies) in jars) {
            val saved = org.json.JSONObject()
            for ((name, value) in cookies) saved.put(name, value)
            root.put(route, saved)
        }
        return root.toString()
    }

    /** Forgets jars of proxies that are no longer on the list. */
    fun prune(jars: MutableMap<String, List<Pair<String, String>>>, proxies: List<ProxyEntry>) {
        val valid = proxies.map { it.rule }.toSet() + DIRECT
        jars.keys.retainAll(valid)
    }

    /** True when the result is PTC's own answer about the account, not a failure on the way. */
    fun reachedPtc(status: String): Boolean =
        status == Protocol.STATUS_SUCCESS || status == Protocol.STATUS_INVALID || status == Protocol.STATUS_BANNED

    fun isKept(name: String): Boolean = KEEP.any { it.matches(name) }

    /** [cookieHeader] is what CookieManager.getCookie returns: "a=1; b=2". */
    fun select(cookieHeader: String?): List<Pair<String, String>> {
        if (cookieHeader.isNullOrBlank()) return emptyList()
        return cookieHeader.split(";").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (isKept(name)) name to value else null
        }
    }

    /** The Set-Cookie text that puts one kept cookie back for the login site. */
    fun restoreHeader(name: String, value: String): String =
        "$name=$value; Path=/; Secure; Max-Age=$RESTORE_MAX_AGE_SECONDS"

    const val DIRECT = "direct"
    private const val RESTORE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60
}
