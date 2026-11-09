package io.github.mgelsinger.smsbridge.platform

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.mgelsinger.smsbridge.core.EventHub
import io.github.mgelsinger.smsbridge.core.SmsRepository
import io.github.mgelsinger.smsbridge.core.SyncCursor
import io.github.mgelsinger.smsbridge.protocol.MessageFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pushes every new provider row to connected clients. Watching the provider,
 * rather than SMS_RECEIVED broadcasts, catches inbound messages after the default
 * SMS app has stored them and messages sent from the phone itself, the same way.
 */
class MessageWatcher(
    context: Context,
    private val repo: SmsRepository,
    private val hub: EventHub,
    private val log: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val resolver = context.contentResolver
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trigger.trySend(Unit)
        }
    }
    private var cursor: SyncCursor? = null
    private var loop: Job? = null
    private var poll: Job? = null
    private var lastError: String? = null

    fun start() {
        resolver.registerContentObserver(Uri.parse("content://sms"), true, observer)
        resolver.registerContentObserver(Uri.parse("content://mms"), true, observer)
        loop = scope.launch {
            trigger.trySend(Unit)
            for (t in trigger) {
                delay(DEBOUNCE_MS)
                scan()
            }
        }
        // Some OEM providers skip change notifications; a slow poll covers them.
        poll = scope.launch {
            while (true) {
                delay(POLL_MS)
                trigger.trySend(Unit)
            }
        }
    }

    fun stop() {
        resolver.unregisterContentObserver(observer)
        loop?.cancel()
        poll?.cancel()
    }

    private fun scan() {
        try {
            var c = cursor ?: repo.latestCursor().also { cursor = it; lastError = null; return }
            var pushed = 0
            while (true) {
                val result = repo.messagesSince(c, BATCH)
                for (m in result.messages) hub.publish(MessageFrame(message = m))
                pushed += result.messages.size
                c = result.next
                cursor = c
                if (!result.hasMore) break
            }
            if (pushed > 0) log("pushed $pushed new message(s)")
            lastError = null
        } catch (e: Exception) {
            val what = e.javaClass.simpleName
            if (what != lastError) log("watcher cannot read messages: $what")
            lastError = what
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
        private const val POLL_MS = 30_000L
        private const val BATCH = 100
    }
}
