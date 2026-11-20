package io.github.mgelsinger.smsbridge

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Telephony
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.mgelsinger.smsbridge.core.BridgeApi
import io.github.mgelsinger.smsbridge.core.EventHub
import io.github.mgelsinger.smsbridge.core.InMemoryClientStore
import io.github.mgelsinger.smsbridge.core.PairingManager
import io.github.mgelsinger.smsbridge.core.SyncCursor
import io.github.mgelsinger.smsbridge.core.SendTracker
import io.github.mgelsinger.smsbridge.platform.AndroidSender
import io.github.mgelsinger.smsbridge.protocol.SendResultFrame
import io.github.mgelsinger.smsbridge.protocol.SendStatus
import java.util.UUID
import io.github.mgelsinger.smsbridge.platform.AndroidSmsRepository
import io.github.mgelsinger.smsbridge.platform.KeystoreIdentity
import io.github.mgelsinger.smsbridge.platform.MessageWatcher
import io.github.mgelsinger.smsbridge.protocol.Direction
import io.github.mgelsinger.smsbridge.protocol.HelloFrame
import io.github.mgelsinger.smsbridge.protocol.MessageKind
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = { line: String -> Log.i("SmsBridgeTest", line); Unit }
    private val identity = KeystoreIdentity.loadOrCreate("sms-bridge-emulator-test")
    private val repo = AndroidSmsRepository(context)
    private val hub = EventHub()
    private val pairing = PairingManager(InMemoryClientStore())
    private val tracker = SendTracker()
    private val sender = AndroidSender(context, repo, tracker, hub, log)
    private val api = BridgeApi(repo, pairing, confirmPairing = { true }, sendHandler = sender, log = log)
    private val server = BridgeServer(api, hub, identity, TlsFront.LOOPBACK_V4, 0, log)
    private val watcher = MessageWatcher(context, repo, hub, log, scope, tracker)
    private var port = 0
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        port = server.start()
        watcher.start()
        // Regression: a test that sent a text within the watcher's first 300 ms never saw
        // the stored copy pushed, because the watcher had not yet noted where "new" begins.
        runBlocking { withTimeout(30_000) { watcher.awaitWatching() } }
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
        sender.unregister()
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
        // Live frames and sync together cover every message, but a message stored just
        // before `hello` can arrive live only. Wait for one that is newer than the hello
        // cursor, which sync from that cursor must then also return.
        val helloCursor = checkNotNull(SyncCursor.decode(hello.syncCursor))
        Log.i("SmsBridgeTest", "hello received at ${hello.syncCursor}, waiting for an injected text")

        val deadline = System.currentTimeMillis() + 240_000
        var inbound: MessageFrame? = null
        while (inbound == null && System.currentTimeMillis() < deadline) {
            val f = frames.poll(5, TimeUnit.SECONDS) ?: continue
            if (f !is MessageFrame) continue
            val newer = f.message.id.startsWith("sms:") && f.message.id.substringAfter(':').toLong() > helloCursor.sms
            if (newer && f.message.direction == Direction.IN && f.message.body?.startsWith("hello from ci") == true) inbound = f
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

    @Test
    fun sendToTheEmulatorsOwnNumberAndSeeItComeBack() {
        val token = pairing.issueToken("EMULATOR-CI").second
        val frames = LinkedBlockingQueue<WsFrame>()
        val ws = http.newWebSocket(
            Request.Builder().url("wss://127.0.0.1:$port/ws").header("Authorization", "Bearer $token").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.add(ProtocolJson.decodeFromString(WsFrame.serializer(), text))
                }
            },
        )
        assertTrue(frames.poll(30, TimeUnit.SECONDS) is HelloFrame)

        // The emulator on console port 5554 answers to 15555215554, so this loops back as an inbound text.
        val clientId = UUID.randomUUID().toString()
        val body = "loopback ${clientId.take(8)}"
        ws.send("""{"protocol_version":1,"type":"send","client_id":"$clientId","to":["15555215554"],"body":"$body"}""")

        val statuses = mutableListOf<SendStatus>()
        var echoed: MessageFrame? = null
        var inbound: MessageFrame? = null
        var messageId: String? = null
        val deadline = System.currentTimeMillis() + 120_000
        // The loopback inbound copy is informational: whether an emulator delivers texts to
        // its own number depends on the image, not on this app.
        while ((SendStatus.SENT !in statuses || echoed == null || messageId == null) && System.currentTimeMillis() < deadline) {
            when (val f = frames.poll(5, TimeUnit.SECONDS)) {
                is SendResultFrame -> if (f.clientId == clientId) {
                    statuses += f.status
                    if (f.messageId != null) messageId = f.messageId
                }
                is MessageFrame -> if (f.message.body == body) {
                    if (f.message.direction == Direction.OUT) echoed = f else inbound = f
                }
                else -> {}
            }
        }
        Log.i("SmsBridgeTest", "send statuses $statuses, echoed ${echoed?.message?.id}, inbound ${inbound?.message?.id}")
        assertEquals(SendStatus.QUEUED, statuses.firstOrNull())
        assertTrue("reached sent: $statuses", SendStatus.SENT in statuses)
        assertTrue("no failure: $statuses", SendStatus.FAILED !in statuses)
        val stored = checkNotNull(echoed) { "the stored copy of the sent text was not pushed" }.message
        assertEquals("the stored copy carries the client_id", clientId, stored.clientId)
        assertEquals("send_result names the stored message", stored.id, messageId)
        if (inbound == null) Log.i("SmsBridgeTest", "no loopback inbound copy on this emulator image")

        // Replaying the same client_id returns the remembered result instead of sending again.
        frames.clear()
        ws.send("""{"protocol_version":1,"type":"send","client_id":"$clientId","to":["15555215554"],"body":"$body"}""")
        // Other frames can arrive first: the loopback inbound copy, or a late text from an
        // earlier test (seen in CI on 2026-11-20). Wait for this client_id's answer itself.
        val skipped = mutableListOf<WsFrame>()
        var replay: SendResultFrame? = null
        val replayDeadline = System.currentTimeMillis() + 10_000
        while (replay == null && System.currentTimeMillis() < replayDeadline) {
            when (val f = frames.poll(1, TimeUnit.SECONDS)) {
                null -> {}
                is SendResultFrame -> if (f.clientId == clientId) replay = f else skipped += f
                else -> skipped += f
            }
        }
        assertTrue("replay answers with a result: $replay, after $skipped", replay != null && replay.status != SendStatus.QUEUED)
        frames.addAll(skipped)
        Thread.sleep(5_000)
        assertTrue("no second copy was sent", frames.none { it is MessageFrame && it.message.body == body && it.message.direction == Direction.OUT })
        ws.close(1000, null)
    }

    /**
     * Regression: on a dozing Pixel every provider query took a few hundred milliseconds, and
     * the thread list made several per thread. Listing 43 threads took over 30 seconds, the PC
     * timed out and never finished connecting. The cost must not grow with the thread count,
     * and the watcher's idle poll must not build the thread list at all.
     */
    @Test
    fun listingThreadsCostsTheSameHoweverManyThereAre() {
        val sms = context.getSystemService(android.telephony.SmsManager::class.java)
        val numbers = listOf("5550001", "5550002", "5550003", "5550004")
        numbers.forEach { sms.sendTextMessage(it, null, "thread cost", null, null) }
        val deadline = System.currentTimeMillis() + 60_000
        var threads = 0
        while (System.currentTimeMillis() < deadline) {
            repo.forgetThreads()
            threads = repo.threadPage(200, null).items.size
            if (threads >= numbers.size) break
            Thread.sleep(1_000)
        }
        assertTrue("the sent texts created threads: $threads", threads >= numbers.size)

        // The watcher shares the repository; keep its queries out of the count.
        watcher.stop()
        Thread.sleep(1_000)
        repo.forgetThreads()
        val before = repo.queryCount.get()
        repo.threadPage(200, null)
        val cost = repo.queryCount.get() - before
        Log.i("SmsBridgeTest", "listing $threads threads took $cost provider queries")
        assertTrue("listing $threads threads took $cost provider queries", cost <= 8)

        val cursor = repo.latestCursor()
        repo.forgetThreads()
        val idleBefore = repo.queryCount.get()
        val idle = repo.messagesSince(cursor, 100)
        val idleCost = repo.queryCount.get() - idleBefore
        assertTrue("nothing is new", idle.messages.isEmpty())
        assertTrue("an idle poll took $idleCost provider queries", idleCost <= 2)
    }

    /**
     * Regression: a page of history made two provider queries for every MMS it scanned, and it
     * scans 50 rows more than a page holds. On a dozing Pixel each query takes 70 to 400 ms, so
     * a page of a picture-heavy conversation took 10 seconds and more. A page now costs three
     * queries, plus one per MMS on it where the thread has more than one other member.
     */
    @Test
    fun aPageOfPictureMessagesCostsAFixedNumberOfQueries() {
        val resolver = context.contentResolver
        val stored = mutableListOf<Long>()
        shell("appops set ${context.packageName} WRITE_SMS allow")
        try {
            val start = repo.latestCursor()
            val seconds = System.currentTimeMillis() / 1000 - 1000
            val friend = "5550201"
            val members = listOf("5550202", "5550203")
            val alone = Telephony.Threads.getOrCreateThreadId(context, friend)
            val group = Telephony.Threads.getOrCreateThreadId(context, members.toSet())
            repeat(12) { i -> stored += storeMms(alone, friend, seconds + i, "one to one $i") }
            repeat(12) { i -> stored += storeMms(group, members[i % 2], seconds + i, "group $i from ${members[i % 2]}") }
            assertTrue("the provider stored the test MMS: $stored", stored.all { it > 0 } && stored.distinct().size == 24)

            // The watcher shares the repository; keep its queries out of the count.
            watcher.stop()
            Thread.sleep(1_000)
            repo.forgetThreads()
            repo.threadPage(200, null) // The thread list is kept for a minute; pages do not pay for it again.
            val friendAddress = checkNotNull(repo.threadParticipants(alone)).single()
            val memberAddresses = checkNotNull(repo.threadParticipants(group))
            assertEquals("both members of the group are listed", 2, memberAddresses.size)

            var before = repo.queryCount.get()
            val first = checkNotNull(repo.messagePage(alone, 5, null))
            var cost = repo.queryCount.get() - before
            Log.i("SmsBridgeTest", "a page of 5 one-to-one MMS took $cost provider queries")
            assertTrue("a page of 5 one-to-one MMS took $cost provider queries", cost <= 3)
            assertEquals((11 downTo 7).map { "one to one $it" }, first.items.map { it.body })
            for (m in first.items) {
                assertEquals(MessageKind.MMS, m.kind)
                assertEquals(Direction.IN, m.direction)
                assertEquals(friendAddress, m.address)
                assertEquals("one picture each: ${m.parts}", listOf("image/jpeg"), m.parts.map { it.contentType })
            }
            assertEquals("no part is handed to two messages", 5, first.items.flatMap { it.parts }.map { it.partId }.distinct().size)
            // Walking back reaches every message once.
            val seen = first.items.map { it.id }.toMutableList()
            var next = first.next
            while (next != null) {
                val page = checkNotNull(repo.messagePage(alone, 5, next))
                seen += page.items.map { it.id }
                next = page.next
            }
            assertEquals(stored.take(12).reversed().map { "mms:$it" }, seen)

            before = repo.queryCount.get()
            val shared = checkNotNull(repo.messagePage(group, 5, null))
            cost = repo.queryCount.get() - before
            Log.i("SmsBridgeTest", "a page of 5 group MMS took $cost provider queries")
            assertTrue("a page of 5 group MMS took $cost provider queries", cost <= 3 + 5)
            assertEquals((11 downTo 7).map { "group $it from ${members[it % 2]}" }, shared.items.map { it.body })
            for (m in shared.items) {
                assertTrue("${m.address} is a member", m.address in memberAddresses)
                assertTrue("the sender is the one who wrote it: ${m.address}, ${m.body}", m.address.endsWith(m.body.orEmpty().substringAfterLast(' ')))
            }

            // Catching up: only the rows that fit the page are looked up, not all 24 that are new.
            repo.forgetThreads()
            before = repo.queryCount.get()
            val sync = repo.messagesSince(start, 4)
            cost = repo.queryCount.get() - before
            Log.i("SmsBridgeTest", "a sync page of 4 with 24 new MMS took $cost provider queries")
            assertTrue("more remain", sync.hasMore)
            assertTrue("a sync page of 4 took $cost provider queries", cost <= 2 + 8 + 1 + 4)
        } finally {
            stored.filter { it > 0 }.forEach { resolver.delete(Uri.parse("content://mms/$it"), null, null) }
            shell("appops set ${context.packageName} WRITE_SMS default")
        }
    }

    private fun shell(command: String) {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }

    /** Stores a received picture message the way the system's messaging app would. Needs the WRITE_SMS app-op. */
    private fun storeMms(threadId: Long, from: String, seconds: Long, text: String): Long {
        val resolver = context.contentResolver
        val message = ContentValues().apply {
            put("thread_id", threadId)
            put("date", seconds)
            put("msg_box", 1) // inbox
            put("read", 1)
            put("m_type", 132) // retrieve-conf, a received message
            put("ct_t", "application/vnd.wap.multipart.related")
        }
        val id = resolver.insert(Telephony.Mms.CONTENT_URI, message)?.lastPathSegment?.toLongOrNull() ?: return -1
        if (id <= 0) return -1
        resolver.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply { put("address", from); put("type", 137); put("charset", 106) })
        resolver.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply { put("address", "insert-address-token"); put("type", 151); put("charset", 106) })
        resolver.insert(Uri.parse("content://mms/$id/part"), ContentValues().apply { put("ct", "application/smil"); put("text", "<smil></smil>") })
        resolver.insert(Uri.parse("content://mms/$id/part"), ContentValues().apply { put("ct", "image/jpeg"); put("name", "picture-$id.jpg") })
        resolver.insert(Uri.parse("content://mms/$id/part"), ContentValues().apply { put("ct", "text/plain"); put("text", text) })
        return id
    }

    /**
     * The Windows client checks TLS handshake signatures with rustls and webpki, which only
     * accept X.509 v3 certificates with ECDSA P-256 keys it knows. Guard what Keystore issues.
     */
    @Test
    fun keystoreCertificateIsOneTheWindowsClientAccepts() {
        val cert = identity.certificate
        Log.i("SmsBridgeTest", "cert v${cert.version} ${cert.sigAlgName} key ${cert.publicKey.algorithm} critical ${cert.criticalExtensionOIDs}")
        assertEquals("X.509 version", 3, cert.version)
        assertEquals("EC", cert.publicKey.algorithm)
        assertEquals(256, (cert.publicKey as java.security.interfaces.ECPublicKey).params.order.bitLength())
        val known = setOf("2.5.29.15", "2.5.29.19", "2.5.29.37", "2.5.29.17", "2.5.29.30")
        val unknownCritical = cert.criticalExtensionOIDs.orEmpty() - known
        assertTrue("critical extensions webpki does not know: $unknownCritical", unknownCritical.isEmpty())
        cert.checkValidity()
    }
}
