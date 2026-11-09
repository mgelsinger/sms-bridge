package io.github.mgelsinger.smsbridge.core

import io.github.mgelsinger.smsbridge.protocol.Contact
import io.github.mgelsinger.smsbridge.protocol.ContactsResponse
import io.github.mgelsinger.smsbridge.protocol.Message
import io.github.mgelsinger.smsbridge.protocol.MessagesPage
import io.github.mgelsinger.smsbridge.protocol.ProtocolJson
import io.github.mgelsinger.smsbridge.protocol.StatusResponse
import io.github.mgelsinger.smsbridge.protocol.ThreadSummary
import io.github.mgelsinger.smsbridge.protocol.ThreadsPage
import java.io.File

fun fixturesDir(): File = File(
    System.getProperty("protocol.fixtures") ?: error("protocol.fixtures system property is not set"),
)

/** An in-memory phone seeded from protocol/fixtures, paging the same way the real repository does. */
class FixtureRepository(root: File = fixturesDir()) : SmsRepository {
    private val threads = ProtocolJson.decodeFromString(ThreadsPage.serializer(), root.resolve("threads-page/default.json").readText())
        .threads.associateBy { it.id }.toMutableMap()
    private val messages: MutableMap<Long, MutableList<Message>> = root.resolve("messages-page").listFiles()!!
        .filter { it.name.startsWith("thread-") }
        .map { ProtocolJson.decodeFromString(MessagesPage.serializer(), it.readText()) }
        .associate { it.threadId to it.messages.toMutableList() }
        .toMutableMap()
    private val contactList = ProtocolJson.decodeFromString(ContactsResponse.serializer(), root.resolve("contacts/default.json").readText()).contacts
    private val status = ProtocolJson.decodeFromString(StatusResponse.serializer(), root.resolve("status/default.json").readText())

    @Synchronized
    fun add(message: Message) {
        messages.getOrPut(message.threadId) { mutableListOf() }.add(message)
        val t = threads[message.threadId]
        threads[message.threadId] = ThreadSummary(
            id = message.threadId,
            participants = t?.participants ?: message.participants,
            snippet = message.body,
            date = message.date,
            unreadCount = (t?.unreadCount ?: 0) + if (message.read) 0 else 1,
            messageCount = (t?.messageCount ?: 0) + 1,
        )
    }

    private fun rowId(m: Message) = m.id.substringAfter(':').toLong()

    @Synchronized
    override fun threadPage(limit: Int, before: ThreadKey?) =
        Paging.newestFirst(threads.values.sortedByDescending { ThreadKey(it.date, it.id) }, limit, before) { ThreadKey(it.date, it.id) }

    @Synchronized
    override fun messagePage(threadId: Long, limit: Int, before: MessageKey?): Page<Message, MessageKey>? {
        val list = messages[threadId] ?: return null
        val key = { m: Message -> MessageKey.of(m.date, m.id) }
        return Paging.newestFirst(list.sortedByDescending(key), limit, before, key)
    }

    @Synchronized
    override fun messagesSince(cursor: SyncCursor, limit: Int): SyncResult {
        val all = messages.values.flatten()
        val sms = all.filter { it.id.startsWith("sms:") && rowId(it) > cursor.sms }.sortedBy(::rowId).take(limit + 1)
        val mms = all.filter { it.id.startsWith("mms:") && rowId(it) > cursor.mms }.sortedBy(::rowId).take(limit + 1)
        return SyncPaging.assemble(cursor, sms.map { rowId(it) to it }, mms.map { rowId(it) to it }, limit)
    }

    @Synchronized
    override fun latestCursor(): SyncCursor {
        val all = messages.values.flatten()
        return SyncCursor(
            all.filter { it.id.startsWith("sms:") }.maxOfOrNull(::rowId) ?: 0,
            all.filter { it.id.startsWith("mms:") }.maxOfOrNull(::rowId) ?: 0,
        )
    }

    override fun contacts(): List<Contact> = contactList

    @Synchronized
    override fun threadParticipants(threadId: Long): List<String>? = threads[threadId]?.participants

    override fun mmsPart(partId: Long): PartBytes? =
        if (partId == 3001L) PartBytes("image/png", ByteArray(236) { it.toByte() }) else null

    override fun deviceInfo() = DeviceInfo(
        deviceName = status.deviceName,
        batteryPercent = status.batteryPercent,
        charging = status.charging,
        signalLevel = status.signalLevel,
        simState = status.simState,
        isDefaultSmsApp = status.isDefaultSmsApp,
    )
}
