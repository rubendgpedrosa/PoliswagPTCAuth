package com.poliswag.passport

/** One proxy for PTC logins. [rule] is what the WebView is given; it never holds credentials. */
data class ProxyEntry(
    val scheme: String,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
) {
    val rule: String get() = "$scheme://${if (host.contains(':')) "[$host]" else host}:$port"

    /** Safe to show in logs: no username or password. */
    val label: String get() = "$scheme $host:$port"

    val hasCredentials: Boolean get() = username.isNotEmpty()
}

data class ProxyParseResult(val proxies: List<ProxyEntry>, val errors: List<String>)

object Proxies {
    private val SCHEMES = setOf("http", "https", "socks5")

    /**
     * Parses one proxy per line. Accepted forms:
     *   host:port
     *   host:port:user:pass
     *   user:pass@host:port
     *   http://, https:// or socks5:// in front of any of the above
     * Blank lines and lines starting with # are skipped. Errors name the line number
     * only, never its text, because a line can hold a password.
     */
    fun parseList(text: String): ProxyParseResult {
        val proxies = mutableListOf<ProxyEntry>()
        val errors = mutableListOf<String>()
        text.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            try {
                proxies.add(parseLine(line))
            } catch (e: IllegalArgumentException) {
                errors.add("line ${index + 1}: ${e.message}")
            }
        }
        return ProxyParseResult(proxies, errors)
    }

    fun parseLine(line: String): ProxyEntry {
        var rest = line.trim()
        var scheme = "http"
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd >= 0) {
            scheme = rest.substring(0, schemeEnd).lowercase()
            require(scheme in SCHEMES) { "scheme must be http, https or socks5" }
            rest = rest.substring(schemeEnd + 3)
        }
        rest = rest.trimEnd('/')

        var username = ""
        var password = ""
        var hostPort = rest
        // The password may itself contain '@', so split on the last one.
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            val creds = rest.substring(0, at)
            hostPort = rest.substring(at + 1)
            val colon = creds.indexOf(':')
            require(colon > 0) { "expected user:pass before @" }
            username = creds.substring(0, colon)
            password = creds.substring(colon + 1)
        } else if (!rest.startsWith("[")) {
            // host:port:user:pass (the password may contain ':')
            val parts = rest.split(":", limit = 4)
            if (parts.size == 4) {
                hostPort = parts[0] + ":" + parts[1]
                username = parts[2]
                password = parts[3]
            }
        }

        val host: String
        val portText: String
        if (hostPort.startsWith("[")) { // [ipv6]:port
            val close = hostPort.indexOf(']')
            require(close > 1 && hostPort.getOrNull(close + 1) == ':') { "expected [ipv6]:port" }
            host = hostPort.substring(1, close)
            portText = hostPort.substring(close + 2)
        } else {
            val colon = hostPort.lastIndexOf(':')
            require(colon > 0) { "expected host:port" }
            host = hostPort.substring(0, colon)
            portText = hostPort.substring(colon + 1)
        }
        require(host.isNotEmpty() && host.none { it.isWhitespace() || it == '/' || it == '@' }) { "bad host" }
        val port = portText.toIntOrNull()
        require(port != null && port in 1..65535) { "bad port" }
        // Android's WebView cannot log in to a SOCKS proxy.
        require(!(scheme == "socks5" && username.isNotEmpty())) {
            "socks5 with a username is not supported; use an http proxy or IP-allowlisted socks5"
        }
        return ProxyEntry(scheme, host, port, username, password)
    }
}
