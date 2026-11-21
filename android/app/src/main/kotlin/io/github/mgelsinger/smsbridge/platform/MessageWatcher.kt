package io.github.mgelsinger.smsbridge.platform

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.mgelsinger.smsbridge.core.EventHub
import io.github.mgelsinger.smsbridge.core.SendTracker
import io.github.mgelsinger.smsbridge.core.SmsRepository
import io.github.mgelsinger.smsbridge.core.SyncCursor
import io.github.mgelsinger.smsbridge.protocol.Direction
import io.github.mgelsinger.smsbridge.protocol.MessageFrame
import kotlinx.coroutines.CompletableDeferred
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
    private val tracker: SendTracker? = null,
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
    private val watching = CompletableDeferred<Unit>()

    /**
     * Returns once the watcher knows where "new" begins. A row stored before that is
     * never pushed, so anything that must see its own row pushed waits for this first.
     */
    suspend fun awaitWatching() = watching.await()

    fun start() {
        resolver.registerContentObserver(Uri.parse("content://sms"), true, observer)
        resolver.registerContentObserver(Uri.parse("content://mms"), true, observer)
        loop = scope.launch {
            // Settle where "new" begins at once, not after the debounce: a text sent in
            // that gap had its stored copy counted as old, and it was never pushed.
            scan()
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
            var c = cursor ?: repo.latestCursor().also {
                cursor = it
                lastError = null
                watching.complete(Unit)
                return
            }
            var pushed = 0
            while (true) {
                val result = repo.messagesSince(c, BATCH)
                for (m in result.messages) {
                    val (message, update) = tracker?.claim(m) ?: (m to null)
                    hub.publish(MessageFrame(message = message))
                    update?.let(hub::publish)
                    // Android 17 holds verification codes back for hours; say when a text came late.
                    val lateBy = System.currentTimeMillis() - message.date
                    if (message.direction == Direction.IN && lateBy > LATE_MS) {
                        log("${message.id} reached the app ${lateBy / 60_000} min after the phone received it")
                    }
                }
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
        private const val LATE_MS = 60_000L
    }
}
