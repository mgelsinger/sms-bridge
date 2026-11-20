package io.github.mgelsinger.smsbridge.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import io.github.mgelsinger.smsbridge.core.CallRows
import io.github.mgelsinger.smsbridge.core.EventHub
import io.github.mgelsinger.smsbridge.core.Hook
import io.github.mgelsinger.smsbridge.core.LineTracker
import io.github.mgelsinger.smsbridge.protocol.CallFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tells connected clients what the line is doing and pushes new call log
 * entries. The line comes from the system's phone-state broadcast, which needs
 * READ_PHONE_STATE and carries the caller's number only with READ_CALL_LOG.
 * Without those permissions nothing arrives and the line stays idle; granting
 * them later starts it working with no restart.
 */
class CallWatcher(
    private val context: Context,
    private val phone: AndroidTelephone,
    private val line: LineTracker,
    private val hub: EventHub,
    private val log: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trigger.trySend(Unit)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            @Suppress("DEPRECATION") // The only way to learn who is ringing without being the dialer.
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            report(hookOf(intent.getStringExtra(TelephonyManager.EXTRA_STATE)), number)
        }
    }
    private var lastId: Long? = null
    private var loop: Job? = null
    private var poll: Job? = null
    private var observing = false
    private val watching = CompletableDeferred<Unit>()

    /** Returns once the watcher has looked at the call log for the first time. */
    suspend fun awaitWatching() = watching.await()

    fun start() {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // The broadcast only reports changes; a call already in progress is read once here.
        @Suppress("DEPRECATION")
        runCatching { context.getSystemService(TelephonyManager::class.java)?.callState }.getOrNull()?.let {
            report(
                when (it) {
                    TelephonyManager.CALL_STATE_RINGING -> Hook.RINGING
                    TelephonyManager.CALL_STATE_OFFHOOK -> Hook.OFF_HOOK
                    else -> Hook.IDLE
                },
                null,
            )
        }
        loop = scope.launch {
            scan()
            watching.complete(Unit)
            for (t in trigger) {
                delay(DEBOUNCE_MS)
                scan()
            }
        }
        // Covers a call log permission granted after the service started.
        poll = scope.launch {
            while (true) {
                delay(POLL_MS)
                trigger.trySend(Unit)
            }
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        if (observing) context.contentResolver.unregisterContentObserver(observer)
        observing = false
        loop?.cancel()
        poll?.cancel()
    }

    private fun hookOf(state: String?): Hook = when (state) {
        TelephonyManager.EXTRA_STATE_RINGING -> Hook.RINGING
        TelephonyManager.EXTRA_STATE_OFFHOOK -> Hook.OFF_HOOK
        else -> Hook.IDLE
    }

    private fun report(hook: Hook, number: String?) {
        val frame = line.report(hook, number?.takeIf { it.isNotBlank() }?.let(phone::normalize)) ?: return
        // The state only: who is calling stays out of the log.
        log("line ${frame.state.name.lowercase()}")
        hub.publish(frame)
        // The call log entry is written a moment after the line goes idle.
        trigger.trySend(Unit)
    }

    private fun scan() {
        try {
            if (!observing && phone.abilities().history) {
                // Registering before the permission is granted throws on some versions.
                context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
                observing = true
            }
            val from = lastId ?: run {
                // Where "new" begins. Entries from before this are history, fetched by GET /calls.
                lastId = phone.latestCallId()
                return
            }
            var after = from
            while (true) {
                val batch = phone.callsSince(after, BATCH) ?: return
                for (call in batch) hub.publish(CallFrame(call = call))
                if (batch.isNotEmpty()) {
                    after = CallRows.rowId(batch.last())
                    lastId = after
                    log("pushed ${batch.size} new call log entr${if (batch.size == 1) "y" else "ies"}")
                }
                if (batch.size < BATCH) break
            }
        } catch (e: Exception) {
            log("watcher cannot read the call log: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
        const val POLL_MS = 30_000L
        const val BATCH = 50
    }
}
