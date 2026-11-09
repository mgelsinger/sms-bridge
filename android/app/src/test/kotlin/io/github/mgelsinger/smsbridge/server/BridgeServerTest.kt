package io.github.mgelsinger.smsbridge.server

import io.github.mgelsinger.smsbridge.core.BridgeApi
import io.github.mgelsinger.smsbridge.core.EventHub
import io.github.mgelsinger.smsbridge.core.FixtureRepository
import io.github.mgelsinger.smsbridge.core.InMemoryClientStore
import io.github.mgelsinger.smsbridge.core.PairingManager
import io.github.mgelsinger.smsbridge.protocol.HelloFrame
import io.github.mgelsinger.smsbridge.protocol.MessageFrame
import io.github.mgelsinger.smsbridge.protocol.PongFrame
import io.github.mgelsinger.smsbridge.protocol.ProtocolJson
import io.github.mgelsinger.smsbridge.protocol.ThreadsPage
import io.github.mgelsinger.smsbridge.protocol.WsFrame
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The whole phone server over real TLS and WebSocket, with the fixture repository behind it. */
class BridgeServerTest {
    private val identity = TlsIdentity.fromPkcs12(
        javaClass.classLoader!!.getResourceAsStream("test-identity.p12")!!,
        "test-only".toCharArray(),
        "sms-bridge-test",
    )
    private val repo = FixtureRepository()
    private val hub = EventHub()
    private val pairing = PairingManager(InMemoryClientStore())
    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private lateinit var server: BridgeServer
    private var port = 0
    private lateinit var token: String

    @BeforeEach
    fun start() {
        val api = BridgeApi(repo, pairing, confirmPairing = { true }, log = { logs += it })
        server = BridgeServer(api, hub, identity, InetAddress.getLoopbackAddress(), 0) { logs += it }
        port = server.start()
        token = pairing.issueToken("TEST-PC").second
    }

    @AfterEach
    fun stop() = server.stop()

    private fun client(pin: String = identity.fingerprint): OkHttpClient {
        val tm = PinnedTrustManager(pin)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true } // Identity is the pinned certificate, not a hostname.
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun get(path: String, auth: String? = token, pin: String = identity.fingerprint): Response =
        client(pin).newCall(
            Request.Builder().url("https://127.0.0.1:$port$path").apply { if (auth != null) header("Authorization", "Bearer $auth") }.build(),
        ).execute()

    private class Socketed(val ws: WebSocket, val frames: LinkedBlockingQueue<WsFrame>, val closes: LinkedBlockingQueue<Int>) {
        fun next(): WsFrame = frames.poll(10, TimeUnit.SECONDS) ?: error("no frame within 10 s")
    }

    private fun connect(auth: String = token, failures: LinkedBlockingQueue<Int>? = null): Socketed {
        val frames = LinkedBlockingQueue<WsFrame>()
        val closes = LinkedBlockingQueue<Int>()
        val ws = client().newWebSocket(
            Request.Builder().url("wss://127.0.0.1:$port/ws").header("Authorization", "Bearer $auth").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.add(ProtocolJson.decodeFromString(WsFrame.serializer(), text))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closes.add(code)
                    webSocket.close(1000, null)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failures?.add(response?.code ?: -1)
                }
            },
        )
        return Socketed(ws, frames, closes)
    }

    @Test
    fun threadsOverPinnedTls() {
        get("/threads").use { r ->
            assertEquals(200, r.code)
            val page = ProtocolJson.decodeFromString(ThreadsPage.serializer(), r.body!!.string())
            assertEquals(listOf(11L, 13L, 14L, 12L), page.threads.map { it.id })
        }
    }

    @Test
    fun requestsWithoutAValidTokenGet401() {
        get("/threads", auth = null).use { assertEquals(401, it.code) }
        get("/threads", auth = "A".repeat(43)).use { assertEquals(401, it.code) }
        get("/status", auth = null).use { assertTrue(it.body!!.string().contains("unauthorized")) }
    }

    @Test
    fun aClientPinnedToAnotherCertificateRefusesTheConnection() {
        assertThrows<SSLHandshakeException> { get("/threads", pin = "0".repeat(64)) }
    }

    @Test
    fun plaintextConnectionsGetNothing() {
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("GET /threads HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val buf = ByteArray(64)
            val n = runCatching { s.getInputStream().read(buf) }.getOrDefault(-1)
            // Whatever comes back (usually nothing, or a TLS alert) is never an HTTP response.
            assertTrue(n <= 0 || !String(buf, 0, n, Charsets.ISO_8859_1).startsWith("HTTP/"))
        }
        get("/status").use { assertEquals(200, it.code) } // The server survives junk.
    }

    @Test
    fun webSocketHelloPingAndLiveMessages() {
        val c = connect()
        val hello = c.next() as HelloFrame
        assertEquals(repo.latestCursor().encode(), hello.syncCursor)
        c.ws.send("""{"protocol_version":1,"type":"ping"}""")
        assertEquals(PongFrame(), c.next())

        val template = repo.messagePage(11, 1, null)!!.items.single()
        val fresh = template.copy(id = "sms:3000", body = "pushed")
        hub.publish(MessageFrame(message = fresh))
        assertEquals(fresh, (c.next() as MessageFrame).message)
        c.ws.close(1000, null)
    }

    @Test
    fun webSocketUpgradeWithoutTokenIsRefused() {
        val failures = LinkedBlockingQueue<Int>()
        connect(auth = "B".repeat(43), failures = failures)
        assertEquals(401, failures.poll(10, TimeUnit.SECONDS))
    }

    @Test
    fun revokingAClientClosesItsSocketWith4001() {
        val c = connect()
        c.next() // hello
        val client = pairing.clients.value.single()
        pairing.revoke(client.id)
        hub.revoke(client.id)
        assertEquals(4001, c.closes.poll(10, TimeUnit.SECONDS))
    }

    @Test
    fun wrongProtocolVersionClosesWith4000() {
        val c = connect()
        c.next()
        c.ws.send("""{"protocol_version":2,"type":"ping"}""")
        assertEquals(4000, c.closes.poll(10, TimeUnit.SECONDS))
    }
}
