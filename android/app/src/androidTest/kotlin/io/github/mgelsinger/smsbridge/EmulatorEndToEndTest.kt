package io.github.mgelsinger.smsbridge

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.mgelsinger.smsbridge.core.BridgeApi
import io.github.mgelsinger.smsbridge.core.EventHub
import io.github.mgelsinger.smsbridge.core.InMemoryClientStore
import io.github.mgelsinger.smsbridge.core.PairingManager
import io.github.mgelsinger.smsbridge.platform.AndroidSmsRepository
import io.github.mgelsinger.smsbridge.platform.KeystoreIdentity
import io.github.mgelsinger.smsbridge.platform.MessageWatcher
import io.github.mgelsinger.smsbridge.protocol.Direction
import io.github.mgelsinger.smsbridge.protocol.HelloFrame
import io.github.mgelsinger.smsbridge.protocol.MessageFrame
import io.github.mgelsinger.smsbridge.protocol.MessagesPage
import io.github.mgelsinger.smsbridge.protocol.PairResponse
import io.github.mgelsinger.smsbridge.protocol.ProtocolJson
import io.github.mgelsinger.smsbridge.protocol.StatusResponse
import io.github.mgelsinger.smsbridge.protocol.SyncPage
import io.github.mgelsinger.smsbridge.protocol.ThreadsPage
import io.github.mgelsinger.smsbridge.protocol.WsFrame
import io.github.mgelsinger.smsbridge.server.BridgeServer
import io.github.mgelsinger.smsbridge.server.PinnedTrustManager
import io.github.mgelsinger.smsbridge.server.TlsFront
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phase 1 gate on an emulator: the real SMS provider, the Android Keystore
 * TLS key, the watcher, and the server. CI injects texts with
 * `adb emu sms send 5551234 "hello from ci N"` while this runs.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorEndToEndTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = { line: String -> Log.i("SmsBridgeTest", line); Unit }
    private val identity = KeystoreIdentity.loadOrCreate("sms-bridge-emulator-test")
    private val repo = AndroidSmsRepository(context)
    private val hub = EventHub()
    private val pairing = PairingManager(InMemoryClientStore())
    private val api = BridgeApi(repo, pairing, confirmPairing = { true }, log = log)
    private val server = BridgeServer(api, hub, identity, TlsFront.LOOPBACK_V4, 0, log)
    private val watcher = MessageWatcher(context, repo, hub, log, scope)
    private var port = 0
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        port = server.start()
        watcher.start()
        val tm = PinnedTrustManager(identity.fingerprint)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        http = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true } // Identity is the pinned certificate.
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        watcher.stop()
        server.stop()
        scope.cancel()
    }

    private fun url(path: String) = "https://127.0.0.1:$port$path"

    private fun get(path: String, token: String?): Pair<Int, String> =
        http.newCall(Request.Builder().url(url(path)).apply { if (token != null) header("Authorization", "Bearer $token") }.build())
            .execute().use { it.code to it.body!!.string() }

    @Test
    fun pairThenReceiveALiveTextAndReadItBack() {
        // Pair over HTTPS, as a PC would, against the Android Keystore key.
        val code = pairing.startSession().code
        val pairBody = """{"protocol_version":1,"pairing_code":"$code","client_name":"EMULATOR-CI"}"""
        val token = http.newCall(
            Request.Builder().url(url("/pair")).post(pairBody.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use {
            assertEquals(200, it.code)
            ProtocolJson.decodeFromString(PairResponse.serializer(), it.body!!.string()).token
        }
        assertEquals(401, get("/threads", null).first)
        val status = ProtocolJson.decodeFromString(StatusResponse.serializer(), get("/status", token).second)
        assertEquals(1, status.pairedClients)

        // Live push over the WebSocket.
        val frames = LinkedBlockingQueue<WsFrame>()
        val ws = http.newWebSocket(
            Request.Builder().url("wss://127.0.0.1:$port/ws").header("Authorization", "Bearer $token").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.add(ProtocolJson.decodeFromString(WsFrame.serializer(), text))
                }
            },
        )
        val hello = frames.poll(30, TimeUnit.SECONDS) as HelloFrame
        Log.i("SmsBridgeTest", "hello received, waiting for an injected text")

        val deadline = System.currentTimeMillis() + 240_000
        var inbound: MessageFrame? = null
        while (inbound == null && System.currentTimeMillis() < deadline) {
            val f = frames.poll(5, TimeUnit.SECONDS) ?: continue
            if (f is MessageFrame && f.message.direction == Direction.IN && f.message.body?.startsWith("hello from ci") == true) inbound = f
        }
        val message = checkNotNull(inbound) { "no injected text reached the WebSocket within 4 minutes" }.message
        Log.i("SmsBridgeTest", "live message ${message.id} in thread ${message.threadId}")
        ws.close(1000, null)

        // The same message through every read path.
        val sync = ProtocolJson.decodeFromString(SyncPage.serializer(), get("/messages?cursor=${hello.syncCursor}", token).second)
        assertTrue("sync from the hello cursor includes ${message.id}", sync.messages.any { it.id == message.id })
        val threads = ProtocolJson.decodeFromString(ThreadsPage.serializer(), get("/threads", token).second)
        assertTrue("thread ${message.threadId} is listed", threads.threads.any { it.id == message.threadId })
        val (code2, body) = get("/threads/${message.threadId}/messages?limit=20", token)
        assertEquals(200, code2)
        val page = ProtocolJson.decodeFromString(MessagesPage.serializer(), body)
        assertTrue("thread page includes ${message.id}", page.messages.any { it.id == message.id })
        assertTrue(page.messages.zipWithNext().all { (a, b) -> a.date >= b.date })
    }
}
