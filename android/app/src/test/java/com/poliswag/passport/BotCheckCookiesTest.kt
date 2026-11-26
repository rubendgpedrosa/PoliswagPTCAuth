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
}
