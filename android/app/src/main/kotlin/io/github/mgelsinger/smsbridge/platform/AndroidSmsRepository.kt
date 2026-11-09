package io.github.mgelsinger.smsbridge.platform

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import io.github.mgelsinger.smsbridge.core.DeviceInfo
import io.github.mgelsinger.smsbridge.core.MessageKey
import io.github.mgelsinger.smsbridge.core.MmsRow
import io.github.mgelsinger.smsbridge.core.Page
import io.github.mgelsinger.smsbridge.core.Paging
import io.github.mgelsinger.smsbridge.core.PartBytes
import io.github.mgelsinger.smsbridge.core.RowMapping
import io.github.mgelsinger.smsbridge.core.SmsRepository
import io.github.mgelsinger.smsbridge.core.SmsRow
import io.github.mgelsinger.smsbridge.core.SyncCursor
import io.github.mgelsinger.smsbridge.core.SyncPaging
import io.github.mgelsinger.smsbridge.core.SyncResult
import io.github.mgelsinger.smsbridge.core.ThreadKey
import io.github.mgelsinger.smsbridge.protocol.Contact
import io.github.mgelsinger.smsbridge.protocol.Message
import io.github.mgelsinger.smsbridge.protocol.MmsPart
import io.github.mgelsinger.smsbridge.protocol.SimState
import io.github.mgelsinger.smsbridge.protocol.ThreadSummary
import java.util.Locale

/** Reads the system SMS and MMS provider. The phone keeps no copy of messages. */
class AndroidSmsRepository(private val context: Context) : SmsRepository {
    private val resolver: ContentResolver = context.contentResolver
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    private data class RawThread(val id: Long, val date: Long, val count: Int, val recipientIds: List<Long>, val snippet: String?)

    private val countryIso: String
        get() = listOf(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }.orEmpty().uppercase(Locale.ROOT)

    /** E.164 when the number parses; short codes and alphanumeric senders stay as they are. */
    private fun normalize(raw: String): String {
        if (raw.any { it.isLetter() }) return raw
        return PhoneNumberUtils.formatNumberToE164(raw, countryIso) ?: raw
    }

    // Threads -------------------------------------------------------------

    private fun rawThreads(): List<RawThread> =
        resolver.query(CONVERSATIONS, arrayOf("_id", "date", "message_count", "recipient_ids", "snippet"), null, null, "date DESC")
            ?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val count = c.getInt(2)
                        if (count <= 0) continue
                        add(
                            RawThread(
                                id = c.getLong(0),
                                date = c.getLong(1),
                                count = count,
                                recipientIds = c.getStringOrNull(3).orEmpty().split(' ').mapNotNull { it.toLongOrNull() },
                                snippet = c.getStringOrNull(4),
                            ),
                        )
                    }
                }
            }.orEmpty()

    private fun canonicalAddresses(): Map<Long, String> =
        resolver.query(CANONICAL_ADDRESSES, arrayOf("_id", "address"), null, null, null)?.use { c ->
            buildMap { while (c.moveToNext()) c.getStringOrNull(1)?.let { put(c.getLong(0), it) } }
        }.orEmpty()

    private fun participantsByThread(): Map<Long, List<String>> {
        val addresses = canonicalAddresses()
        return rawThreads().associate { t -> t.id to t.recipientIds.mapNotNull { addresses[it] }.map(::normalize).distinct() }
    }

    override fun threadPage(limit: Int, before: ThreadKey?): Page<ThreadSummary, ThreadKey> {
        val all = rawThreads().sortedByDescending { ThreadKey(it.date, it.id) }
        val page = Paging.newestFirst(all, limit, before) { ThreadKey(it.date, it.id) }
        val addresses = canonicalAddresses()
        val items = page.items.map { t ->
            ThreadSummary(
                id = t.id,
                participants = t.recipientIds.mapNotNull { addresses[it] }.map(::normalize).distinct().ifEmpty { listOf(UNKNOWN) },
                snippet = t.snippet?.take(200),
                date = t.date,
                unreadCount = unreadCount(t.id),
                messageCount = t.count,
            )
        }
        return Page(items, page.next)
    }

    private fun unreadCount(threadId: Long): Int {
        val sms = count(Telephony.Sms.CONTENT_URI, "thread_id = ? AND read = 0 AND type = ${RowMapping.SMS_INBOX}", threadId)
        val mms = count(Telephony.Mms.CONTENT_URI, "thread_id = ? AND read = 0 AND msg_box = ${RowMapping.MMS_INBOX}", threadId)
        return sms + mms
    }

    private fun count(uri: Uri, selection: String, threadId: Long): Int =
        resolver.query(uri, arrayOf("_id"), selection, arrayOf(threadId.toString()), null)?.use { it.count } ?: 0

    // Messages ------------------------------------------------------------

    override fun messagePage(threadId: Long, limit: Int, before: MessageKey?): Page<Message, MessageKey>? {
        val participants = participantsByThread()
        if (threadId !in participants) return null
        val people = participants.getValue(threadId)
        val take = limit + PAGE_SLACK
        val smsSel = "thread_id = ?" + if (before != null) " AND date <= ?" else ""
        val smsArgs = listOfNotNull(threadId.toString(), before?.date?.toString()).toTypedArray()
        val sms = querySms(smsSel, smsArgs, "date DESC, _id DESC", take, mapOf(threadId to people)).mapNotNull { it.second }
        val mmsArgs = listOfNotNull(threadId.toString(), before?.date?.let { (it / 1000).toString() }).toTypedArray()
        val mms = queryMms(smsSel, mmsArgs, "date DESC, _id DESC", take, people).mapNotNull { it.second }
        val key = { m: Message -> MessageKey.of(m.date, m.id) }
        return Paging.newestFirst((sms + mms).sortedByDescending(key), limit, before, key)
    }

    override fun messagesSince(cursor: SyncCursor, limit: Int): SyncResult {
        val participants = participantsByThread()
        val sms = querySms("_id > ?", arrayOf(cursor.sms.toString()), "_id ASC", limit + 1, participants)
        val mms = queryMmsAll("_id > ?", arrayOf(cursor.mms.toString()), "_id ASC", limit + 1, participants)
        return SyncPaging.assemble(cursor, sms, mms, limit)
    }

    override fun latestCursor(): SyncCursor = SyncCursor(maxId(Telephony.Sms.CONTENT_URI), maxId(Telephony.Mms.CONTENT_URI))

    private fun maxId(uri: Uri): Long =
        resolver.query(uri, arrayOf("_id"), null, null, "_id DESC")?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    /** Reads at most [max] rows; the cursor window keeps this cheap without a LIMIT clause. */
    private fun querySms(
        selection: String,
        args: Array<String>,
        order: String,
        max: Int,
        participants: Map<Long, List<String>> = emptyMap(),
    ): List<Pair<Long, Message?>> =
        resolver.query(Telephony.Sms.CONTENT_URI, SMS_COLUMNS, selection, args, order)?.use { c ->
            buildList {
                while (size < max && c.moveToNext()) {
                    val row = SmsRow(
                        id = c.getLong(0),
                        threadId = c.getLong(1),
                        address = c.getStringOrNull(2),
                        body = c.getStringOrNull(3),
                        date = c.getLong(4),
                        type = c.getInt(5),
                        read = c.getInt(6) != 0,
                        status = c.getInt(7),
                    )
                    add(row.id to RowMapping.sms(row, participants[row.threadId].orEmpty(), ::normalize))
                }
            }
        }.orEmpty()

    private fun queryMms(selection: String, args: Array<String>, order: String, max: Int, people: List<String>) =
        queryMmsWith(selection, args, order, max) { people }

    private fun queryMmsAll(selection: String, args: Array<String>, order: String, max: Int, participants: Map<Long, List<String>>) =
        queryMmsWith(selection, args, order, max) { participants[it].orEmpty() }

    private fun queryMmsWith(
        selection: String,
        args: Array<String>,
        order: String,
        max: Int,
        peopleFor: (Long) -> List<String>,
    ): List<Pair<Long, Message?>> =
        resolver.query(Telephony.Mms.CONTENT_URI, MMS_COLUMNS, selection, args, order)?.use { c ->
            buildList {
                while (size < max && c.moveToNext()) {
                    val id = c.getLong(0)
                    val threadId = c.getLong(1)
                    val (from, to) = mmsAddresses(id)
                    val (text, parts) = mmsParts(id)
                    val row = MmsRow(
                        id = id,
                        threadId = threadId,
                        dateSeconds = c.getLong(2),
                        messageBox = c.getInt(3),
                        read = c.getInt(4) != 0,
                        from = from,
                        to = to,
                        text = text,
                        parts = parts,
                    )
                    add(id to RowMapping.mms(row, peopleFor(threadId), ::normalize))
                }
            }
        }.orEmpty()

    private fun mmsAddresses(mmsId: Long): Pair<String?, List<String>> {
        var from: String? = null
        val recipients = mutableListOf<String>()
        resolver.query(Uri.parse("content://mms/$mmsId/addr"), arrayOf("address", "type"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val address = c.getStringOrNull(0) ?: continue
                when (c.getInt(1)) {
                    PDU_FROM -> from = address
                    PDU_TO, PDU_CC -> recipients += address
                }
            }
        }
        return from to recipients
    }

    private fun mmsParts(mmsId: Long): Pair<String?, List<MmsPart>> {
        val text = StringBuilder()
        val parts = mutableListOf<MmsPart>()
        resolver.query(MMS_PARTS, arrayOf("_id", "ct", "text", "name", "cl"), "mid = ?", arrayOf(mmsId.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val ct = c.getStringOrNull(1) ?: continue
                if (ct.equals("text/plain", ignoreCase = true)) {
                    c.getStringOrNull(2)?.let { if (text.isNotEmpty()) text.append('\n'); text.append(it) }
                } else if (RowMapping.isAttachment(ct)) {
                    parts += MmsPart(partId = c.getLong(0), contentType = ct, size = null, name = c.getStringOrNull(3) ?: c.getStringOrNull(4))
                }
            }
        }
        return text.toString().ifEmpty { null } to parts
    }

    override fun mmsPart(partId: Long): PartBytes? {
        val uri = Uri.parse("content://mms/part/$partId")
        val ct = resolver.query(uri, arrayOf("ct"), null, null, null)?.use { if (it.moveToFirst()) it.getStringOrNull(0) else null }
            ?: return null
        if (!RowMapping.isAttachment(ct)) return null
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_PART_BYTES) { "part too large" }
            }
            out.toByteArray()
        } ?: return null
        return PartBytes(ct, bytes)
    }

    // Contacts and status -------------------------------------------------

    override fun contacts(): List<Contact> {
        val phones = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val byId = LinkedHashMap<Long, Pair<String, MutableSet<String>>>()
        try {
            resolver.query(phones, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getStringOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
                    val number = c.getStringOrNull(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    byId.getOrPut(c.getLong(0)) { name.take(200) to linkedSetOf() }.second += normalize(number)
                }
            }
        } catch (e: SecurityException) {
            return emptyList() // Contacts permission not granted; names fall back to numbers on the PC.
        }
        return byId.map { (id, v) -> Contact(id = id, name = v.first, numbers = v.second.toList()) }
    }

    override fun deviceInfo(): DeviceInfo {
        val battery = context.getSystemService(BatteryManager::class.java)
        val capacity = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val name = Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL
        return DeviceInfo(
            deviceName = name.take(64),
            batteryPercent = capacity,
            charging = battery?.isCharging,
            signalLevel = runCatching { telephony?.signalStrength?.level }.getOrNull()?.takeIf { it in 0..4 },
            simState = when (telephony?.simState) {
                TelephonyManager.SIM_STATE_READY -> SimState.READY
                TelephonyManager.SIM_STATE_ABSENT -> SimState.ABSENT
                TelephonyManager.SIM_STATE_PIN_REQUIRED,
                TelephonyManager.SIM_STATE_PUK_REQUIRED,
                TelephonyManager.SIM_STATE_NETWORK_LOCKED,
                -> SimState.LOCKED
                else -> SimState.UNKNOWN
            },
            isDefaultSmsApp = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName,
        )
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    companion object {
        private val CONVERSATIONS: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
        private val CANONICAL_ADDRESSES: Uri = Uri.parse("content://mms-sms/canonical-addresses")
        private val MMS_PARTS: Uri = Uri.parse("content://mms/part")
        private val SMS_COLUMNS = arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "status")
        private val MMS_COLUMNS = arrayOf("_id", "thread_id", "date", "msg_box", "read")
        private const val PDU_FROM = 137
        private const val PDU_TO = 151
        private const val PDU_CC = 130
        private const val PAGE_SLACK = 50
        private const val MAX_PART_BYTES = 20 * 1024 * 1024
        private const val UNKNOWN = "unknown"
    }
}
