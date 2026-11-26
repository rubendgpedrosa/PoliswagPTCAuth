package com.poliswag.passport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun validateJobUrl_acceptsValidPokemonAccessUrl() {
        val result = UrlUtils.validateJobUrl("https://access.pokemon.com/oauth2/auth?client_id=abc&code_challenge=xyz")
        assertTrue(result.ok)
        assertNull(result.reason)
    }

    @Test
    fun validateJobUrl_rejectsHttpScheme() {
        val result = UrlUtils.validateJobUrl("http://access.pokemon.com/oauth2/auth")
        assertFalse(result.ok)
    }

    @Test
    fun validateJobUrl_rejectsOtherHostsAndSubdomains() {
        assertFalse(UrlUtils.validateJobUrl("https://evil.com/oauth2/auth").ok)
        assertFalse(UrlUtils.validateJobUrl("https://sub.access.pokemon.com/oauth2/auth").ok)
        assertFalse(UrlUtils.validateJobUrl("https://access.pokemon.com.evil.com/oauth2/auth").ok)
        assertFalse(UrlUtils.validateJobUrl("https://access.pokemon.com@evil.com/oauth2/auth").ok)
    }

    @Test
    fun validateJobUrl_rejectsEmptyAndMalformed() {
        assertFalse(UrlUtils.validateJobUrl(null).ok)
        assertFalse(UrlUtils.validateJobUrl("").ok)
        assertFalse(UrlUtils.validateJobUrl("not a url").ok)
    }

    @Test
    fun extractLoginCode_handlesPokemongoDeepLink() {
        val url = "pokemongo://state=abc123,code=AUTH_CODE_987"
        assertEquals("AUTH_CODE_987", UrlUtils.extractLoginCodeFromRedirectUrl(url))
    }

    @Test
    fun extractLoginCode_handlesRedirectUriQueryAndFragment() {
        assertEquals(
            "code_from_query",
            UrlUtils.extractLoginCodeFromRedirectUrl("https://example.com/callback?state=xyz&code=code_from_query&foo=bar")
        )
        assertEquals(
            "first_param_code",
            UrlUtils.extractLoginCodeFromRedirectUrl("https://example.com/callback?code=first_param_code")
        )
        assertEquals(
            "hash_code",
            UrlUtils.extractLoginCodeFromRedirectUrl("https://example.com/callback#code=hash_code")
        )
    }

    @Test
    fun extractLoginCode_percentDecodesWithoutClobberingPlus() {
        val url = "pokemongo://state=s,code=abc%2Fdef%2B123%3D%3D+rawplus"
        assertEquals("abc/def+123==+rawplus", UrlUtils.extractLoginCodeFromRedirectUrl(url))
    }

    @Test
    fun extractLoginCode_rejectsAccessPokemonComFalsePositive() {
        val url = "https://access.pokemon.com/oauth2/auth?state=1&code=not_final_redirect"
        assertNull(UrlUtils.extractLoginCodeFromRedirectUrl(url))
    }

    @Test
    fun extractLoginCode_ignoresCodeChallengeParam() {
        val url = "https://example.com/callback?code_challenge=abcdef&state=1"
        assertNull(UrlUtils.extractLoginCodeFromRedirectUrl(url))
    }

    @Test
    fun sanitizeUserAgent_stripsWvAndVersion4() {
        val defaultUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.122 Mobile Safari/537.36"
        val expected = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
        assertEquals(expected, UrlUtils.sanitizeUserAgent(defaultUa))
    }
}
