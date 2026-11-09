package io.github.mgelsinger.smsbridge.platform

import android.app.Application
import android.content.Context
import io.github.mgelsinger.smsbridge.core.BridgeApi
import io.github.mgelsinger.smsbridge.core.ClientStore
import io.github.mgelsinger.smsbridge.core.EventHub
import io.github.mgelsinger.smsbridge.core.PairedClient
import io.github.mgelsinger.smsbridge.core.PairingManager
import io.github.mgelsinger.smsbridge.server.TlsIdentity
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class SmsBridgeApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

val Context.graph: AppGraph get() = (applicationContext as SmsBridgeApp).graph

/** Process-wide objects shared by the service and the settings screen. */
class AppGraph(val context: Context) {
    val log = EventLog()
    val settings = BridgeSettings(context)
    private val clientStore = PrefsClientStore(context)
    val pairing = PairingManager(clientStore)
    val hub = EventHub()
    val repo = AndroidSmsRepository(context)
    val confirmations = PairingConfirmations()
    val identity: TlsIdentity by lazy { KeystoreIdentity.loadOrCreate() }
    val api: BridgeApi by lazy {
        BridgeApi(repo, pairing, confirmPairing = { name -> confirmations.request(context, name) }, log = log::add)
    }

    private val _state = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val state: StateFlow<ServiceState> = _state.asStateFlow()
    fun setState(s: ServiceState) { _state.value = s }

    fun revoke(client: PairedClient) {
        pairing.revoke(client.id)
        hub.revoke(client.id)
        log.add("revoked ${client.name}")
    }
}

sealed interface ServiceState {
    data object Stopped : ServiceState
    data object WaitingForWifi : ServiceState
    data class Running(val host: String, val port: Int) : ServiceState
    data class Failed(val reason: String) : ServiceState
}

class BridgeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }
}

/** Paired PCs, persisted as JSON. Only token hashes are stored. */
class PrefsClientStore(context: Context) : ClientStore {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(PairedClient.serializer())

    @Synchronized
    override fun all(): List<PairedClient> =
        prefs.getString("clients", null)?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    @Synchronized
    override fun put(client: PairedClient) = save(all().filter { it.id != client.id } + client)

    @Synchronized
    override fun remove(id: String) = save(all().filter { it.id != id })

    private fun save(list: List<PairedClient>) {
        prefs.edit().putString("clients", Json.encodeToString(serializer, list)).commit()
    }
}

/** Recent events for the settings screen. Never holds message content. */
class EventLog {
    data class Line(val at: Long, val text: String)

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    @Synchronized
    fun add(text: String) {
        android.util.Log.i("SmsBridge", text)
        _lines.value = (listOf(Line(System.currentTimeMillis(), text)) + _lines.value).take(200)
    }

    companion object {
        fun format(line: Line): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(line.at)) + "  " + line.text
    }
}

/** Holds a pairing request until the user accepts or declines it on the phone. */
class PairingConfirmations {
    class Pending(val clientName: String, val decision: CompletableDeferred<Boolean>)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    suspend fun request(context: Context, clientName: String): Boolean {
        val p = Pending(clientName, CompletableDeferred())
        _pending.value = p
        Notifications.pairingRequest(context, clientName)
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { p.decision.await() } ?: false
        } finally {
            _pending.compareAndSet(p, null)
            Notifications.cancelPairingRequest(context)
        }
    }

    fun answer(accept: Boolean) {
        _pending.value?.decision?.complete(accept)
    }

    companion object {
        private const val TIMEOUT_MS = 60_000L
    }
}
