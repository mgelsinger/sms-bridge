package io.github.mgelsinger.smsbridge.core

import io.github.mgelsinger.smsbridge.protocol.Contact
import io.github.mgelsinger.smsbridge.protocol.Message
import io.github.mgelsinger.smsbridge.protocol.SimState
import io.github.mgelsinger.smsbridge.protocol.ThreadSummary

/** Read access to the phone's messages. The Android implementation reads the system SMS provider. */
interface SmsRepository {
    fun threadPage(limit: Int, before: ThreadKey?): Page<ThreadSummary, ThreadKey>

    /** Null when the thread does not exist. */
    fun messagePage(threadId: Long, limit: Int, before: MessageKey?): Page<Message, MessageKey>?

    /** Messages with row ids above [cursor], SMS first then MMS, each in id order. */
    fun messagesSince(cursor: SyncCursor, limit: Int): SyncResult

    /** Cursor pointing at the newest row of each table right now. */
    fun latestCursor(): SyncCursor

    fun contacts(): List<Contact>

    fun mmsPart(partId: Long): PartBytes?

    fun deviceInfo(): DeviceInfo
}

data class SyncResult(val messages: List<Message>, val next: SyncCursor, val hasMore: Boolean)

class PartBytes(val contentType: String, val bytes: ByteArray)

data class DeviceInfo(
    val deviceName: String,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val signalLevel: Int?,
    val simState: SimState,
    val isDefaultSmsApp: Boolean,
)
