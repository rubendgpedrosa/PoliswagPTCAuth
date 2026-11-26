package com.poliswag.passport

import org.json.JSONObject
import java.net.URLEncoder

sealed class IncomingMessage {
    data class Job(
        val id: String,
        val username: String,
        val password: String,
        val url: String
    ) : IncomingMessage()

    data class Cancel(
        val id: String,
        val status: String
    ) : IncomingMessage()
}

object Protocol {
    const val CLOSE_CODE_REPLACED = 4001

    const val STATUS_SUCCESS = "SUCCESS"
    const val STATUS_INVALID = "INVALID"
    const val STATUS_BANNED = "BANNED"
    const val STATUS_ERROR = "ERROR"

    fun parseIncoming(text: String): IncomingMessage? {
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        return when (obj.optString("type", "")) {
            "job" -> {
                val id = obj.optString("id", "")
                if (id.isEmpty()) return null
                IncomingMessage.Job(
                    id = id,
                    username = obj.optString("username", ""),
                    password = obj.optString("password", ""),
                    url = obj.optString("url", "")
                )
            }
            "cancel" -> {
                val id = obj.optString("id", "")
                if (id.isEmpty()) return null
                val status = obj.optString("status", "CANCELLED").ifEmpty { "CANCELLED" }
                IncomingMessage.Cancel(id = id, status = status)
            }
            else -> null
        }
    }

    fun serializeResult(id: String, status: String, loginCode: String? = null): String {
        val obj = JSONObject()
            .put("type", "result")
            .put("id", id)
            .put("status", status)
        if (!loginCode.isNullOrEmpty()) {
            obj.put("login_code", loginCode)
        }
        return obj.toString()
    }

    fun serializeChallenge(id: String): String {
        return JSONObject()
            .put("type", "challenge")
            .put("id", id)
            .toString()
    }

    /** Says in plain words why connecting to the helper failed, and what to check. */
    fun describeConnectFailure(httpCode: Int?, error: Throwable?): String = when {
        httpCode == 403 -> "helper refused this worker: check the Worker Token (and that WORKER_TOKEN is set on the helper)"
        httpCode == 400 -> "helper rejected the worker ID"
        httpCode != null -> "that address answered HTTP $httpCode: check the Helper URL (it should end in /ws)"
        error is java.net.UnknownHostException -> "helper address not found: check the Helper URL"
        error is java.net.ConnectException -> "cannot reach the helper: is it running, and is the address and port right?"
        error is java.net.SocketTimeoutException -> "helper did not answer: check the address, the port and the firewall"
        error is javax.net.ssl.SSLException -> "secure connection failed: check the certificate, or use ws:// on your own network"
        else -> error?.javaClass?.simpleName ?: "connection error"
    }

    // The token is never put in the URL: reverse proxies log URLs. It goes in the
    // Authorization header instead.
    fun buildWebSocketUrl(baseUrl: String, workerId: String): String {
        val trimmed = baseUrl.trim()
        require(trimmed.startsWith("ws://", ignoreCase = true) || trimmed.startsWith("wss://", ignoreCase = true)) {
            "Helper URL must start with ws:// or wss://"
        }
        val sep = if (trimmed.contains("?")) "&" else "?"
        val encodedWorker = encodeQueryParam(workerId)
        val sb = StringBuilder(trimmed)
            .append(sep)
            .append("worker=")
            .append(encodedWorker)
        return sb.toString()
    }

    fun shortId(id: String?): String {
        return if (id.isNullOrEmpty()) "unknown" else id.take(8)
    }

    private fun encodeQueryParam(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
