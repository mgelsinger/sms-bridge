package io.github.mgelsinger.smsbridge.server

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Terminates TLS on [bind]:[port] and forwards each connection's plaintext to
 * the HTTP server on loopback:[targetPort]. Ktor's CIO engine, the one that runs
 * on Android, has no TLS support, so TLS lives here with the phone's own key.
 */
class TlsFront(
    private val identity: TlsIdentity,
    private val bind: InetAddress,
    private val port: Int,
    private val targetPort: Int,
    private val log: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "tls-front").apply { isDaemon = true }
    }
    private lateinit var server: SSLServerSocket

    /** Starts listening and returns the bound port. */
    fun start(): Int {
        server = (identity.sslContext.serverSocketFactory.createServerSocket(port, BACKLOG, bind) as SSLServerSocket).apply {
            val wanted = listOf("TLSv1.3", "TLSv1.2")
            enabledProtocols = wanted.filter { it in supportedProtocols }.toTypedArray()
            needClientAuth = false
        }
        running.set(true)
        pool.execute(::acceptLoop)
        return server.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                server.accept() as SSLSocket
            } catch (e: IOException) {
                if (running.get()) log("TLS accept failed: ${e.javaClass.simpleName}")
                continue
            }
            if (!slots.tryAcquire()) {
                runCatching { socket.close() }
                log("TLS connection refused: too many connections")
                continue
            }
            pool.execute {
                try {
                    relay(socket)
                } finally {
                    slots.release()
                }
            }
        }
    }

    private fun relay(client: SSLSocket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 0
            client.startHandshake()
            upstream = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), 5_000)
            }
            val up = upstream
            val toServer = pool.submit { pump(client.inputStream, up.outputStream) { closeQuietly(client, up) } }
            pump(up.inputStream, client.outputStream) { closeQuietly(client, up) }
            toServer.cancel(true)
        } catch (e: IOException) {
            // Failed handshakes (wrong pin on the client, port scans) end up here. No detail is logged.
            log("TLS connection ended: ${e.javaClass.simpleName}")
        } finally {
            closeQuietly(client, upstream)
        }
    }

    private fun pump(from: InputStream, to: OutputStream, onEnd: () -> Unit) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
            // Either side closed.
        } finally {
            onEnd()
        }
    }

    private fun closeQuietly(vararg sockets: Socket?) {
        for (s in sockets) runCatching { s?.close() }
    }

    companion object {
        private const val BACKLOG = 16
        private const val MAX_CONNECTIONS = 32
    }
}
