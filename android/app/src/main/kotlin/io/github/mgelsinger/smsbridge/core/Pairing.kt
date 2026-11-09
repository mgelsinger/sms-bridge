package io.github.mgelsinger.smsbridge.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class PairedClient(
    val id: String,
    val name: String,
    /** SHA-256 of the bearer token, lowercase hex. The token itself is never stored. */
    val tokenHash: String,
    val pairedAt: Long,
    val lastSeen: Long? = null,
)

interface ClientStore {
    fun all(): List<PairedClient>
    fun put(client: PairedClient)
    fun remove(id: String)
}

class InMemoryClientStore : ClientStore {
    private val clients = LinkedHashMap<String, PairedClient>()
    @Synchronized override fun all() = clients.values.toList()
    @Synchronized override fun put(client: PairedClient) { clients[client.id] = client }
    @Synchronized override fun remove(id: String) { clients.remove(id) }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Pairing sessions, lockout, and token issue and check. PROTOCOL.md "Pairing":
 * 6-digit single-use code valid for 2 minutes; 5 failures in 10 minutes locks
 * pairing for 10 minutes; tokens are 32 random bytes, stored only as hashes.
 */
class PairingManager(
    private val store: ClientStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    data class Session(val code: String, val expiresAt: Long)

    enum class CodeCheck { VALID, INVALID, LOCKED }

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _clients = MutableStateFlow(store.all())
    val clients: StateFlow<List<PairedClient>> = _clients.asStateFlow()

    private val failures = ArrayDeque<Long>()
    private var lockedUntil = 0L

    @Synchronized
    fun startSession(): Session {
        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        return Session(code, now() + SESSION_TTL_MS).also { _session.value = it }
    }

    @Synchronized
    fun cancelSession() {
        _session.value = null
    }

    /** Checks a code. A valid code is consumed, so it works once whatever happens next. */
    @Synchronized
    fun checkCode(code: String): CodeCheck {
        val t = now()
        if (t < lockedUntil) return CodeCheck.LOCKED
        val s = _session.value
        val ok = s != null && t < s.expiresAt &&
            MessageDigest.isEqual(code.toByteArray(), s.code.toByteArray())
        if (ok) {
            _session.value = null
            return CodeCheck.VALID
        }
        while (failures.isNotEmpty() && t - failures.first() >= FAILURE_WINDOW_MS) failures.removeFirst()
        failures.addLast(t)
        if (failures.size >= MAX_FAILURES) {
            lockedUntil = t + LOCKOUT_MS
            failures.clear()
        }
        return CodeCheck.INVALID
    }

    /** Creates a client and returns its token. Call only after a VALID code and user confirmation. */
    @Synchronized
    fun issueToken(clientName: String): Pair<PairedClient, String> {
        val tokenBytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val idBytes = ByteArray(8).also(random::nextBytes)
        val client = PairedClient(
            id = idBytes.joinToString("") { "%02x".format(it) },
            name = clientName.take(64),
            tokenHash = sha256Hex(token.toByteArray()),
            pairedAt = now(),
        )
        store.put(client)
        _clients.value = store.all()
        return client to token
    }

    /** The client for an `Authorization` header value, or null. */
    fun authenticate(authorization: String?): PairedClient? {
        val token = authorization?.let { BEARER.matchEntire(it)?.groupValues?.get(1) } ?: return null
        val hash = sha256Hex(token.toByteArray()).toByteArray()
        val client = store.all().firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), hash) } ?: return null
        val t = now()
        if (client.lastSeen == null || t - client.lastSeen > LAST_SEEN_WRITE_INTERVAL_MS) {
            synchronized(this) {
                store.put(client.copy(lastSeen = t))
                _clients.value = store.all()
            }
        }
        return client
    }

    @Synchronized
    fun revoke(id: String) {
        store.remove(id)
        _clients.value = store.all()
    }

    companion object {
        const val SESSION_TTL_MS = 120_000L
        const val MAX_FAILURES = 5
        const val FAILURE_WINDOW_MS = 600_000L
        const val LOCKOUT_MS = 600_000L
        private const val LAST_SEEN_WRITE_INTERVAL_MS = 60_000L
        private val BEARER = Regex("^Bearer ([A-Za-z0-9_-]{43})$")
    }
}
