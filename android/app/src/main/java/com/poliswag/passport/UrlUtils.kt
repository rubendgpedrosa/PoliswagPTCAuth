package com.poliswag.passport

import java.io.ByteArrayOutputStream
import java.net.URI

data class ValidationResult(
    val ok: Boolean,
    val reason: String? = null
)

object UrlUtils {
    private const val ALLOWED_HOST = "access.pokemon.com"
    private val CODE_PARAM_REGEX = Regex("""[?#&,]code=([^&,#'"\s]+)""")

    fun validateJobUrl(rawUrl: String?): ValidationResult {
        if (rawUrl.isNullOrBlank()) {
            return ValidationResult(false, "missing job URL")
        }
        val uri = try {
            URI(rawUrl)
        } catch (_: Exception) {
            return ValidationResult(false, "malformed job URL")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") {
            return ValidationResult(false, "disallowed URL protocol: ${uri.scheme ?: "none"}")
        }
        val host = uri.host?.lowercase()
        if (host != ALLOWED_HOST) {
            return ValidationResult(false, "disallowed URL host: ${uri.host ?: "none"}")
        }
        if (uri.rawUserInfo != null) {
            return ValidationResult(false, "disallowed URL userinfo")
        }
        return ValidationResult(true)
    }

    /**
     * Extracts and percent-decodes an OAuth login code from a redirect URL.
     * Matches `pokemongo://...,code=<code>` deep links and any URL NOT on
     * `access.pokemon.com` containing `?code=`, `&code=`, `#code=`, or `,code=`.
     */
    fun extractLoginCodeFromRedirectUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrEmpty()) return null
        val isDeepLink = rawUrl.startsWith("pokemongo://", ignoreCase = true)
        if (!isDeepLink) {
            val uri = try {
                URI(rawUrl)
            } catch (_: Exception) {
                return null
            }
            val host = uri.host ?: return null
            if (host.equals(ALLOWED_HOST, ignoreCase = true)) {
                return null
            }
        }
        val match = CODE_PARAM_REGEX.find(rawUrl) ?: return null
        val rawCode = match.groupValues[1]
        if (rawCode.isEmpty()) return null
        return percentDecode(rawCode)
    }

    /**
     * Percent-decodes UTF-8 `%XX` sequences without turning `+` into space
     * (matching JavaScript's `decodeURIComponent`). Falls back to input on malformed `%`.
     */
    fun percentDecode(input: String): String {
        if (!input.contains('%')) return input
        return try {
            val out = ByteArrayOutputStream(input.length)
            var i = 0
            while (i < input.length) {
                val c = input[i]
                if (c == '%') {
                    if (i + 2 >= input.length) return input
                    val hi = Character.digit(input[i + 1], 16)
                    val lo = Character.digit(input[i + 2], 16)
                    if (hi == -1 || lo == -1) return input
                    out.write((hi shl 4) or lo)
                    i += 3
                } else {
                    val bytes = c.toString().toByteArray(Charsets.UTF_8)
                    out.write(bytes)
                    i += 1
                }
            }
            out.toString(Charsets.UTF_8.name())
        } catch (_: Exception) {
            input
        }
    }

    /**
     * Strips `; wv` and `Version/4.0 ` markers from the default Android WebView User-Agent.
     */
    fun sanitizeUserAgent(defaultUserAgent: String): String {
        return defaultUserAgent
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }
}
