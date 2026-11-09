package io.github.mgelsinger.smsbridge.core

import io.github.mgelsinger.smsbridge.protocol.ErrorBody
import io.github.mgelsinger.smsbridge.protocol.ErrorCode
import io.github.mgelsinger.smsbridge.protocol.ErrorFrame
import io.github.mgelsinger.smsbridge.protocol.MessagesPage
import io.github.mgelsinger.smsbridge.protocol.PairResponse
import io.github.mgelsinger.smsbridge.protocol.PongFrame
import io.github.mgelsinger.smsbridge.protocol.ProtocolJson
import io.github.mgelsinger.smsbridge.protocol.StatusResponse
import io.github.mgelsinger.smsbridge.protocol.SyncPage
import io.github.mgelsinger.smsbridge.protocol.ThreadsPage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeApiTest {
    private val repo = FixtureRepository()
    private val pairing = PairingManager(InMemoryClientStore())
    private var confirm = true
    private val logs = mutableListOf<String>()
    private val api = BridgeApi(repo, pairing, confirmPairing = { confirm }, log = { logs += it })

    private fun get(path: String, query: Map<String, String> = emptyMap()) = runBlocking { api.handle("GET", path, query, null) }
    private fun <T> decode(s: KSerializer<T>, r: ApiResponse): T = ProtocolJson.decodeFromString(s, r.body.decodeToString())
    private fun errorCode(r: ApiResponse) = decode(ErrorBody.serializer(), r).error.code

    @Test
    fun threadsComeNewestFirstAndPageWithOpaqueCursors() {
        val all = decode(ThreadsPage.serializer(), get("/threads"))
        assertEquals(listOf(11L, 13L, 14L, 12L), all.threads.map { it.id })
        assertNull(all.nextCursor)

        val first = decode(ThreadsPage.serializer(), get("/threads", mapOf("limit" to "3")))
        val rest = decode(ThreadsPage.serializer(), get("/threads", mapOf("limit" to "3", "cursor" to first.nextCursor!!)))
        assertEquals(listOf(12L), rest.threads.map { it.id })
        assertEquals(ErrorCode.BAD_CURSOR, errorCode(get("/threads", mapOf("cursor" to "o:3"))))
        assertEquals(ErrorCode.BAD_REQUEST, errorCode(get("/threads", mapOf("limit" to "0"))))
        assertEquals(ErrorCode.BAD_REQUEST, errorCode(get("/threads", mapOf("limit" to "201"))))
    }

    @Test
    fun threadMessagesPageNewestFirst() {
        val page = decode(MessagesPage.serializer(), get("/threads/11/messages", mapOf("limit" to "2")))
        assertEquals(listOf("sms:1007", "sms:1002"), page.messages.map { it.id })
        val rest = decode(MessagesPage.serializer(), get("/threads/11/messages", mapOf("cursor" to page.nextCursor!!)))
        assertEquals(listOf("sms:1001"), rest.messages.map { it.id })
        assertEquals(404, get("/threads/999/messages").status)
    }

    @Test
    fun syncWithoutCursorStartsFromNowThenPicksUpNewMessages() {
        val start = decode(SyncPage.serializer(), get("/messages"))
        assertTrue(start.messages.isEmpty())
        val template = repo.messagePage(11, 1, null)!!.items.single()
        repo.add(template.copy(id = "sms:2000", body = "new", date = template.date + 1))
        val next = decode(SyncPage.serializer(), get("/messages", mapOf("cursor" to start.nextCursor)))
        assertEquals(listOf("sms:2000"), next.messages.map { it.id })
        val after = decode(SyncPage.serializer(), get("/messages", mapOf("cursor" to next.nextCursor)))
        assertTrue(after.messages.isEmpty())
        assertEquals(ErrorCode.BAD_CURSOR, errorCode(get("/messages", mapOf("cursor" to "t5.5"))))
    }

    @Test
    fun statusCountsPairedClients() {
        pairing.issueToken("A")
        val status = decode(StatusResponse.serializer(), get("/status"))
        assertEquals(1, status.pairedClients)
        assertEquals("Pixel 9", status.deviceName)
    }

    @Test
    fun mmsPartsAndUnknownPaths() {
        val part = get("/mms/3001")
        assertEquals(200, part.status)
        assertEquals("image/png", part.contentType)
        assertEquals(404, get("/mms/1").status)
        assertEquals(404, get("/nope").status)
        assertEquals(404, runBlocking { api.handle("DELETE", "/threads", emptyMap(), null) }.status)
    }

    private fun pair(code: String) = runBlocking {
        api.handle("POST", "/pair", emptyMap(), """{"protocol_version":1,"pairing_code":"$code","client_name":"MIDGAR"}""")
    }

    @Test
    fun pairingIssuesAWorkingTokenOnlyAfterConfirmation() {
        val s = pairing.startSession()
        assertEquals(ErrorCode.PAIRING_INVALID, errorCode(pair(if (s.code == "000000") "111111" else "000000")))
        val ok = pair(s.code)
        assertEquals(200, ok.status)
        val token = decode(PairResponse.serializer(), ok).token
        assertNotNull(api.authenticate("Bearer $token"))
        assertEquals(ErrorCode.PAIRING_INVALID, errorCode(pair(s.code)), "a code works once")

        confirm = false
        val declined = pair(pairing.startSession().code)
        assertEquals(ErrorCode.PAIRING_REJECTED, errorCode(declined))
        assertEquals(1, pairing.clients.value.size)
    }

    @Test
    fun pairingRejectsMalformedBodies() {
        pairing.startSession()
        for (body in listOf("", "{}", """{"protocol_version":1,"pairing_code":"12345","client_name":"x"}""", """{"protocol_version":1,"pairing_code":"123456","client_name":""}""")) {
            val r = runBlocking { api.handle("POST", "/pair", emptyMap(), body) }
            assertEquals(400, r.status, body)
        }
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, errorCode(runBlocking { api.handle("POST", "/pair", emptyMap(), """{"protocol_version":2,"pairing_code":"123456","client_name":"x"}""") }))
    }

    @Test
    fun framesPingMarkReadSendAndVersion() = runBlocking {
        val (client, _) = pairing.issueToken("A")
        assertEquals(listOf(PongFrame()), api.onFrame(client, """{"protocol_version":1,"type":"ping"}""").frames)
        assertEquals(ErrorCode.NOT_DEFAULT_SMS_APP, (api.onFrame(client, """{"protocol_version":1,"type":"mark_read","thread_id":11}""").frames.single() as ErrorFrame).code)

        val cid = "3f0e6c1a-9b2d-4c7e-8a51-2d6f0b9e4c13"
        val both = api.onFrame(client, """{"protocol_version":1,"type":"send","client_id":"$cid","thread_id":1,"to":["+1"],"body":"x"}""").frames.single() as ErrorFrame
        assertEquals(ErrorCode.BAD_REQUEST, both.code)
        assertEquals(cid, both.clientId)
        val badId = api.onFrame(client, """{"protocol_version":1,"type":"send","client_id":"nope","thread_id":1,"body":"x"}""").frames.single() as ErrorFrame
        assertNull(badId.clientId, "a malformed client_id is not echoed")

        val wrongVersion = api.onFrame(client, """{"protocol_version":2,"type":"ping"}""")
        assertEquals(4000, wrongVersion.close?.first)
        assertTrue(api.onFrame(client, """{"protocol_version":1,"type":"from_the_future"}""").frames.isEmpty())
        assertEquals(ErrorCode.BAD_REQUEST, (api.onFrame(client, "not json").frames.single() as ErrorFrame).code)
    }

    @Test
    fun logsNeverContainMessageBodies() {
        get("/threads/11/messages")
        get("/messages", mapOf("cursor" to "s0.m0"))
        val bodies = repo.messagePage(11, 100, null)!!.items.mapNotNull { it.body }
        assertTrue(logs.isNotEmpty())
        for (line in logs) for (body in bodies) assertTrue(body !in line, "log line leaked a body: $line")
    }
}
