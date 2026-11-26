package com.poliswag.passport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotCheckCookiesTest {
    @Test
    fun keepsOnlyBotCheckCookies() {
        val header = "visid_incap_123=abc==; ory_hydra_session=SECRET; incap_ses_45_123=x=y; " +
            "oauth2_authentication_session=S; reese84=tok; nlbi_123=n; ___utmvc=u; csrf_token_x=c; language=en"
        assertEquals(
            listOf("visid_incap_123" to "abc==", "incap_ses_45_123" to "x=y", "reese84" to "tok", "nlbi_123" to "n", "___utmvc" to "u"),
            BotCheckCookies.select(header)
        )
    }

    @Test
    fun unknownAndLookalikeNamesAreDropped() {
        assertTrue(BotCheckCookies.select("session=1; reese84x=1; my_visid_incap_1=1; =broken; novalue").isEmpty())
        assertTrue(BotCheckCookies.select(null).isEmpty())
        assertTrue(BotCheckCookies.select("  ").isEmpty())
    }

    @Test
    fun restoreHeaderIsHostOnlySecureCookie() {
        assertEquals("reese84=tok; Path=/; Secure; Max-Age=604800", BotCheckCookies.restoreHeader("reese84", "tok"))
    }

    @Test
    fun jarsSurviveSaveAndLoadPerRoute() {
        val jars = mutableMapOf(
            "direct" to listOf("reese84" to "d1"),
            "http://a:1" to listOf("visid_incap_9" to "a=1", "incap_ses_1_9" to "a2")
        )
        val loaded = BotCheckCookies.loadJars(BotCheckCookies.dumpJars(jars))
        assertEquals(listOf("reese84" to "d1"), loaded["direct"])
        assertEquals(setOf("visid_incap_9" to "a=1", "incap_ses_1_9" to "a2"), loaded["http://a:1"]!!.toSet())
    }

    @Test
    fun loadJarsDropsUnknownCookiesAndSurvivesBadText() {
        val loaded = BotCheckCookies.loadJars("""{"direct":{"reese84":"t","ory_hydra_session":"SECRET"},"http://b:2":{"session":"x"}}""")
        assertEquals(listOf("reese84" to "t"), loaded["direct"])
        assertTrue(!loaded.containsKey("http://b:2"))
        assertTrue(BotCheckCookies.loadJars("not json").isEmpty())
        assertTrue(BotCheckCookies.loadJars(null).isEmpty())
    }

    @Test
    fun pruneForgetsProxiesNoLongerOnTheList() {
        val jars = mutableMapOf(
            "direct" to listOf("reese84" to "d"),
            "http://a:1" to listOf("reese84" to "a"),
            "http://gone:9" to listOf("reese84" to "g")
        )
        BotCheckCookies.prune(jars, listOf(Proxies.parseLine("a:1")))
        assertEquals(setOf("direct", "http://a:1"), jars.keys)
    }

    @Test
    fun routeNamesTheProxyWithoutCredentials() {
        assertEquals("direct", BotCheckCookies.routeOf(null))
        assertEquals("http://host:8080", BotCheckCookies.routeOf(Proxies.parseLine("user:secret@host:8080")))
    }

    @Test
    fun onlyPtcsOwnAnswersCountAsReached() {
        assertTrue(BotCheckCookies.reachedPtc("SUCCESS"))
        assertTrue(BotCheckCookies.reachedPtc("INVALID"))
        assertTrue(BotCheckCookies.reachedPtc("BANNED"))
        assertTrue(!BotCheckCookies.reachedPtc("ERROR"))
        assertTrue(!BotCheckCookies.reachedPtc("TIMEOUT"))
    }
}
