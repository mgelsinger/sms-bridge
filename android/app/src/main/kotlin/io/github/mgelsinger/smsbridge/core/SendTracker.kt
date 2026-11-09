package io.github.mgelsinger.smsbridge.core

import io.github.mgelsinger.smsbridge.protocol.Direction
import io.github.mgelsinger.smsbridge.protocol.Message
import io.github.mgelsinger.smsbridge.protocol.SendResultFrame
import io.github.mgelsinger.smsbridge.protocol.SendStatus

/**
 * Bookkeeping for sends: per-part progress into queued, sent, delivered, or
 * failed; 24-hour memory of client_ids so a replayed send is never sent twice;
 * and matching the provider row the system writes for each sent message back
 * to the client_id that asked for it.
 */
class SendTracker(private val now: () -> Long = System::currentTimeMillis) {
    private class Entry(
        val clientId: String,
        val address: String,
        val body: String,
        val startedAt: Long,
        val parts: Int,
    ) {
        var sent = 0
        var delivered = 0
        var failed: String? = null
        var messageId: String? = null
        var status = SendStatus.QUEUED
        var lastResult: SendResultFrame? = null
    }

    private val entries = LinkedHashMap<String, Entry>()

    /** The latest result for a client_id seen in the last 24 hours. */
    @Synchronized
    fun known(clientId: String): SendResultFrame? {
        prune()
        return entries[clientId]?.lastResult
    }

    @Synchronized
    fun begin(clientId: String, address: String, body: String, parts: Int): SendResultFrame {
        prune()
        val e = Entry(clientId, address, body, now(), parts.coerceAtLeast(1))
        entries[clientId] = e
        return result(e)
    }

    /** A part's sent-intent fired. Returns a frame when the overall status changes. */
    @Synchronized
    fun partSent(clientId: String, ok: Boolean, error: String? = null): SendResultFrame? {
        val e = entries[clientId] ?: return null
        if (e.status == SendStatus.FAILED) return null
        if (!ok) {
            e.failed = error ?: "send failed"
            e.status = SendStatus.FAILED
            return result(e)
        }
        e.sent++
        if (e.sent >= e.parts && e.status == SendStatus.QUEUED) {
            e.status = SendStatus.SENT
            return result(e)
        }
        return null
    }

    /** A part's delivery report arrived. */
    @Synchronized
    fun partDelivered(clientId: String, ok: Boolean): SendResultFrame? {
        val e = entries[clientId] ?: return null
        if (e.status == SendStatus.FAILED || e.status == SendStatus.DELIVERED) return null
        if (!ok) {
            e.failed = "delivery failed"
            e.status = SendStatus.FAILED
            return result(e)
        }
        e.delivered++
        if (e.delivered >= e.parts) {
            e.status = SendStatus.DELIVERED
            return result(e)
        }
        return null
    }

    /**
     * For a provider row that may be the stored copy of one of our sends: returns
     * the message with its client_id attached, plus a send_result carrying the
     * message id when the send had already reported progress without it.
     */
    @Synchronized
    fun claim(message: Message): Pair<Message, SendResultFrame?> {
        if (message.direction != Direction.OUT || message.clientId != null) return message to null
        val t = now()
        val e = entries.values.firstOrNull {
            it.messageId == null && t - it.startedAt < CLAIM_WINDOW_MS &&
                it.body == message.body && sameNumber(it.address, message.address)
        } ?: return message to null
        e.messageId = message.id
        val update = if (e.status != SendStatus.QUEUED) result(e) else null
        return message.copy(clientId = e.clientId) to update
    }

    private fun result(e: Entry): SendResultFrame =
        SendResultFrame(clientId = e.clientId, messageId = e.messageId, status = e.status, error = e.failed)
            .also { e.lastResult = it }

    private fun prune() {
        val t = now()
        entries.values.removeAll { t - it.startedAt > MEMORY_MS }
    }

    companion object {
        const val MEMORY_MS = 24L * 60 * 60 * 1000
        private const val CLAIM_WINDOW_MS = 5L * 60 * 1000

        /** Compares phone numbers by their last 10 digits, so +15855550101 matches 5855550101. */
        fun sameNumber(a: String, b: String): Boolean {
            val da = a.filter(Char::isDigit)
            val db = b.filter(Char::isDigit)
            if (da.isEmpty() || db.isEmpty()) return a.equals(b, ignoreCase = true)
            return da.takeLast(10) == db.takeLast(10)
        }
    }
}
