package io.github.mgelsinger.smsbridge.server

import io.github.mgelsinger.smsbridge.core.BridgeApi
import io.github.mgelsinger.smsbridge.core.EventHub
import io.github.mgelsinger.smsbridge.core.PairedClient
import io.github.mgelsinger.smsbridge.protocol.WsFrame
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.net.InetAddress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Protocol v1 over HTTPS and WebSocket: a Ktor server on loopback behind a
 * [TlsFront] bound to the Wi-Fi address. Nothing listens in plaintext off-device.
 */
class BridgeServer(
    private val api: BridgeApi,
    private val hub: EventHub,
    private val identity: TlsIdentity,
    private val bind: InetAddress,
    private val port: Int,
    private val log: (String) -> Unit,
) {
    private var http: EmbeddedServer<*, *>? = null
    private var tls: TlsFront? = null

    /** Starts both layers and returns the TLS port. */
    @Synchronized
    fun start(): Int {
        check(http == null) { "already started" }
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets) {
                maxFrameSize = 64 * 1024
                masking = false
            }
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.path() == "/pair") return@intercept
                val client = api.authenticate(call.request.headers[HttpHeaders.Authorization])
                if (client == null) {
                    val r = BridgeApi.unauthorized()
                    call.respondBytes(r.body, ContentType.parse(r.contentType), HttpStatusCode.Unauthorized)
                    finish()
                } else {
                    call.attributes.put(CLIENT, client)
                }
            }
            routing {
                webSocket("/ws") {
                    val client = call.attributes[CLIENT]
                    log("WS connected ${client.id}")
                    // Subscribe before hello so nothing published in between is lost,
                    // but send hello first as PROTOCOL.md requires.
                    val queue = Channel<WsFrame>(256, BufferOverflow.DROP_OLDEST)
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) { hub.frames.collect { queue.trySend(it) } }
                    send(Frame.Text(api.encode(api.hello())))
                    val forwarder = launch { for (f in queue) send(Frame.Text(api.encode(f))) }
                    val revoker = launch {
                        hub.revoked.filter { it == client.id }.first()
                        close(CloseReason(4001, "token revoked"))
                    }
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val reply = api.onFrame(client, frame.readText())
                            for (f in reply.frames) send(Frame.Text(api.encode(f)))
                            reply.close?.let { (code, reason) ->
                                close(CloseReason(code.toShort(), reason))
                                return@webSocket
                            }
                        }
                    } finally {
                        collector.cancel()
                        forwarder.cancel()
                        revoker.cancel()
                        log("WS closed ${client.id}")
                    }
                }
                route("{...}") {
                    handle {
                        val method = call.request.httpMethod.value
                        val body = if (method == "POST") call.receiveText() else null
                        val query = call.request.queryParameters.entries().associate { (k, v) -> k to v.first() }
                        val r = api.handle(method, call.request.path(), query, body)
                        call.respondBytes(r.body, ContentType.parse(r.contentType), HttpStatusCode.fromValue(r.status))
                    }
                }
            }
        }.start(wait = false)
        val innerPort = runBlocking { server.engine.resolvedConnectors().first().port }
        val front = TlsFront(identity, bind, port, innerPort, log)
        val tlsPort = front.start()
        http = server
        tls = front
        log("listening on ${bind.hostAddress}:$tlsPort")
        return tlsPort
    }

    @Synchronized
    fun stop() {
        tls?.stop()
        http?.stop(500, 1_000)
        tls = null
        http = null
    }

    private companion object {
        val CLIENT = AttributeKey<PairedClient>("client")
    }
}
