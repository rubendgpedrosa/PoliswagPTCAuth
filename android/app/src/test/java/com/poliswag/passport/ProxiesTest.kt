package com.poliswag.passport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxiesTest {
    @Test
    fun parsesCommonForms() {
        assertEquals(ProxyEntry("http", "1.2.3.4", 8080), Proxies.parseLine("1.2.3.4:8080"))
        assertEquals(ProxyEntry("http", "p.example.com", 3128, "user", "pa:ss"), Proxies.parseLine("p.example.com:3128:user:pa:ss"))
        assertEquals(ProxyEntry("http", "host", 80, "user", "p@ss:word"), Proxies.parseLine("http://user:p@ss:word@host:80/"))
        assertEquals(ProxyEntry("https", "host", 443), Proxies.parseLine("HTTPS://host:443"))
        assertEquals(ProxyEntry("socks5", "10.0.0.1", 1080), Proxies.parseLine("socks5://10.0.0.1:1080"))
        assertEquals(ProxyEntry("http", "::1", 8080, "u", "p"), Proxies.parseLine("u:p@[::1]:8080"))
    }

    @Test
    fun ruleAndLabelNeverContainCredentials() {
        val p = Proxies.parseLine("http://user:secret@host:8080")
        assertEquals("http://host:8080", p.rule)
        assertFalse(p.label.contains("secret") || p.label.contains("user"))
        assertTrue(p.hasCredentials)
        assertEquals("http://[::1]:8080", Proxies.parseLine("[::1]:8080").rule)
    }

    @Test
    fun parseListSkipsBlanksAndReportsLineNumbersOnly() {
        val r = Proxies.parseList("\n# comment\n1.2.3.4:8080\nuser:secret@nope\nftp://h:1\nsocks5://u:secret@h:1080\nh:99999\n")
        assertEquals(listOf(ProxyEntry("http", "1.2.3.4", 8080)), r.proxies)
        assertEquals(4, r.errors.size)
        assertTrue(r.errors[0].startsWith("line 4:"))
        assertFalse(r.errors.any { it.contains("secret") })
    }

    @Test
    fun emptyTextMeansNoProxy() {
        val r = Proxies.parseList("  \n")
        assertTrue(r.proxies.isEmpty() && r.errors.isEmpty())
    }
}
