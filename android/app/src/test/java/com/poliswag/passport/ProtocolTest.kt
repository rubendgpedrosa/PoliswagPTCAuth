package com.poliswag.passport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun parseIncoming_parsesJobMessage() {
        val raw = """{"type":"job","id":"job-123","username":"ash","password":"secret","url":"https://access.pokemon.com/login"}"""
        val msg = Protocol.parseIncoming(raw)
        assertTrue(msg is IncomingMessage.Job)
        val job = msg as IncomingMessage.Job
        assertEquals("job-123", job.id)
        assertEquals("ash", job.username)
        assertEquals("secret", job.password)
        assertEquals("https://access.pokemon.com/login", job.url)
    }

    @Test
    fun parseIncoming_parsesCancelMessage() {
        val raw = """{"type":"cancel","id":"job-456","status":"TIMEOUT"}"""
        val msg = Protocol.parseIncoming(raw)
        assertTrue(msg is IncomingMessage.Cancel)
        val cancel = msg as IncomingMessage.Cancel
        assertEquals("job-456", cancel.id)
        assertEquals("TIMEOUT", cancel.status)
    }

    @Test
    fun parseIncoming_defaultsCancelStatusWhenMissing() {
        val raw = """{"type":"cancel","id":"job-789"}"""
        val msg = Protocol.parseIncoming(raw) as IncomingMessage.Cancel
        assertEquals("CANCELLED", msg.status)
    }

    @Test
    fun parseIncoming_returnsNullForInvalidOrUnknownMessages() {
        assertNull(Protocol.parseIncoming("not json"))
        assertNull(Protocol.parseIncoming("""{"type":"unknown","id":"1"}"""))
        assertNull(Protocol.parseIncoming("""{"type":"job"}"""))
    }

    @Test
    fun serializeResult_includesLoginCodeOnlyWhenPresent() {
        val successJson = JSONObject(Protocol.serializeResult("id-1", Protocol.STATUS_SUCCESS, "code-xyz"))
        assertEquals("result", successJson.getString("type"))
        assertEquals("id-1", successJson.getString("id"))
        assertEquals("SUCCESS", successJson.getString("status"))
        assertEquals("code-xyz", successJson.getString("login_code"))

        val invalidJson = JSONObject(Protocol.serializeResult("id-2", Protocol.STATUS_INVALID))
        assertEquals("result", invalidJson.getString("type"))
        assertEquals("id-2", invalidJson.getString("id"))
        assertEquals("INVALID", invalidJson.getString("status"))
        assertFalse(invalidJson.has("login_code"))
    }

    @Test
    fun serializeChallenge_formatsExpectedJson() {
        val json = JSONObject(Protocol.serializeChallenge("job-999"))
        assertEquals("challenge", json.getString("type"))
        assertEquals("job-999", json.getString("id"))
    }

    @Test
    fun buildWebSocketUrl_appendsWorkerWithoutToken() {
        val url = Protocol.buildWebSocketUrl("wss://passport.example.com/ws", "android-abc123")
        assertEquals("wss://passport.example.com/ws?worker=android-abc123", url)
    }

    @Test
    fun buildWebSocketUrl_appendsWithAmpersandWhenQueryExists() {
        val url = Protocol.buildWebSocketUrl("ws://192.168.1.5:5090/ws?foo=bar", "android-1")
        assertEquals("ws://192.168.1.5:5090/ws?foo=bar&worker=android-1", url)
    }

    @Test
    fun describeConnectFailure_pointsAtTheLikelyCause() {
        assertEquals(true, Protocol.describeConnectFailure(403, null).contains("Worker Token"))
        assertEquals(true, Protocol.describeConnectFailure(404, null).contains("Helper URL"))
        assertEquals(true, Protocol.describeConnectFailure(null, java.net.ConnectException("x")).contains("cannot reach"))
        assertEquals(true, Protocol.describeConnectFailure(null, java.net.UnknownHostException("h")).contains("not found"))
        // Unknown errors show only the error type, never a message that could hold a URL.
        assertEquals("IllegalStateException", Protocol.describeConnectFailure(null, IllegalStateException("ws://h/ws?token=secret")))
    }
}
