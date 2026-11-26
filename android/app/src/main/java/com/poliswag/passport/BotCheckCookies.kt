package com.poliswag.passport

/**
 * Picks the cookies that survive between logins.
 *
 * Every login must start with no PTC session, or the next account could be signed in
 * as the previous one. But the bot check on the login site (Imperva) also stores
 * cookies on that same site, and wiping those makes each login look like a first-time
 * visitor. So: wipe everything, then put back only the cookies whose names are known
 * to belong to the bot check. A cookie we do not recognise is never kept.
 */
object BotCheckCookies {
    private val KEEP = listOf(
        Regex("^visid_incap_.+"),   // Imperva: long-lived visitor ID
        Regex("^incap_ses_.+"),     // Imperva: session
        Regex("^nlbi_.+"),          // Imperva: load balancer
        Regex("^reese84$"),         // Imperva: advanced bot protection token
        Regex("^___utmvc$")         // Imperva: challenge answer
    )

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

    private const val RESTORE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60
}
