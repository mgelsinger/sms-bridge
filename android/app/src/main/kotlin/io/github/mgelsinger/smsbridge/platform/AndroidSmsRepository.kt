package io.github.mgelsinger.smsbridge.platform

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import io.github.mgelsinger.smsbridge.core.Addressed
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
import io.github.mgelsinger.smsbridge.core.ThreadIndex
import io.github.mgelsinger.smsbridge.core.ThreadInfo
import io.github.mgelsinger.smsbridge.core.ThreadKey
import io.github.mgelsinger.smsbridge.core.ThreadTally
import io.github.mgelsinger.smsbridge.protocol.Contact
import io.github.mgelsinger.smsbridge.protocol.Message
import io.github.mgelsinger.smsbridge.protocol.MmsPart
import io.github.mgelsinger.smsbridge.protocol.SimState
import io.github.mgelsinger.smsbridge.protocol.ThreadSummary
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Reads the system SMS and MMS provider. The phone keeps no copy of messages. */
class AndroidSmsRepository(private val context: Context) : SmsRepository {
    private val resolver: ContentResolver = context.contentResolver
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    private val countryIso: String
        get() = listOf(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }.orEmpty().uppercase(Locale.ROOT)

    /** E.164 when the number parses; short codes and alphanumeric senders stay as they are. */
    private fun normalize(raw: String): String {
        if (raw.any { it.isLetter() }) return raw
        return PhoneNumberUtils.formatNumberToE164(raw, countryIso) ?: raw
    }

    /**
     * Provider queries made so far. Each one takes a few hundred milliseconds on a dozing
     * phone, so tests hold the number per call down.
     */
    val queryCount = AtomicInteger()

    private fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor? {
        queryCount.incrementAndGet()
        return resolver.query(uri, projection, selection, args, order)
    }

    // Threads -------------------------------------------------------------

    /** The threads the system's index lists for this app. On Android 17 that is not all of them. */
    private fun indexedThreads(): List<ThreadInfo> {
        val addresses = canonicalAddresses()
        return query(CONVERSATIONS, arrayOf("_id", "date", "message_count", "recipient_ids", "snippet"), null, null, "date DESC")
            ?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val count = c.getInt(2)
                        if (count <= 0) continue
                        val recipientIds = c.getStringOrNull(3).orEmpty().split(' ').mapNotNull { it.toLongOrNull() }
                        add(
                            ThreadInfo(
                                id = c.getLong(0),
                                date = c.getLong(1),
                                count = count,
                                participants = recipientIds.mapNotNull { addresses[it] }.map(::normalize).distinct(),
                                snippet = c.getStringOrNull(4),
                            ),
                        )
                    }
                }
            }.orEmpty()
    }

    private fun canonicalAddresses(): Map<Long, String> =
        query(CANONICAL_ADDRESSES, arrayOf("_id", "address"), null, null, null)?.use { c ->
            buildMap { while (c.moveToNext()) c.getStringOrNull(1)?.let { put(c.getLong(0), it) } }
        }.orEmpty()

    private class MmsRef(val id: Long, val date: Long, val outgoing: Boolean)

    /**
     * What one pass over the message tables found: a tally for every thread, and for
     * threads the index does not list, what is needed to rebuild them.
     */
    private class MessageScan(
        val tallies: Map<Long, ThreadTally>,
        val smsAddresses: Map<Long, Set<String>>,
        /** Thread to (date, row id) of its newest SMS. */
        val latestSms: Map<Long, Pair<Long, Long>>,
        /** Thread to its newest few MMS, newest first. */
        val newestMms: Map<Long, List<MmsRef>>,
    )

    /**
     * Two queries, whatever the number of threads. Each provider query costs a few
     * hundred milliseconds on a dozing phone, so nothing here asks per thread.
     */
    private fun scanMessages(indexed: Set<Long>): MessageScan {
        val rows = ArrayList<Pair<Long, Long>>()
        val smsAddresses = HashMap<Long, MutableSet<String>>()
        val latestSms = HashMap<Long, Pair<Long, Long>>()
        val newestMms = HashMap<Long, MutableList<MmsRef>>()
        query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("thread_id", "date", "_id", "address"),
            "type != ${RowMapping.SMS_DRAFT}",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val thread = c.getLong(0)
                val date = c.getLong(1)
                rows += thread to date
                if (thread in indexed) continue
                c.getStringOrNull(3)?.let { smsAddresses.getOrPut(thread) { LinkedHashSet() } += it }
                if (date >= (latestSms[thread]?.first ?: Long.MIN_VALUE)) latestSms[thread] = date to c.getLong(2)
            }
        }
        query(
            Telephony.Mms.CONTENT_URI,
            arrayOf("thread_id", "date", "_id", "msg_box"),
            "msg_box != ${RowMapping.MMS_DRAFTS}",
            null,
            "date DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val thread = c.getLong(0)
                val date = c.getLong(1) * 1000
                rows += thread to date
                if (thread in indexed) continue
                val newest = newestMms.getOrPut(thread) { ArrayList() }
                if (newest.size < REBUILD_MMS_SCAN) newest += MmsRef(c.getLong(2), date, c.getInt(3) != RowMapping.MMS_INBOX)
            }
        }
        return MessageScan(ThreadIndex.tally(rows.asSequence()), smsAddresses, latestSms, newestMms)
    }

    private fun smsBodies(ids: Collection<Long>): Map<Long, String?> {
        if (ids.isEmpty()) return emptyMap()
        return query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "body"), "_id IN (${ids.joinToString(",")})", null, null)?.use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getStringOrNull(1)) }
        }.orEmpty()
    }

    private fun mmsTexts(ids: Collection<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val selection = "mid IN (${ids.joinToString(",")}) AND ct LIKE 'text/plain'"
        return query(MMS_PARTS, arrayOf("mid", "text"), selection, null, null)?.use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val text = c.getStringOrNull(1) ?: continue
                    if (!containsKey(c.getLong(0))) put(c.getLong(0), text)
                }
            }
        }.orEmpty()
    }

    /** Thread to (newest MMS id it was worked out from, members). MMS address rows cost one query per message. */
    private val mmsOnlyPeople = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, List<String>>>()

    private fun peopleFromMms(thread: Long, newest: List<MmsRef>): List<String> {
        val newestId = newest.firstOrNull()?.id ?: return emptyList()
        mmsOnlyPeople[thread]?.let { if (it.first == newestId) return it.second }
        val people = ThreadIndex.people(
            newest.map { ref ->
                val (from, to) = mmsAddresses(ref.id)
                Addressed(outgoing = ref.outgoing, from = from, to = to)
            },
            ::normalize,
        )
        mmsOnlyPeople[thread] = newestId to people
        return people
    }

    /** Every thread: the index, completed from the messages. */
    private fun buildThreads(): List<ThreadInfo> {
        val indexed = indexedThreads()
        val known = indexed.mapTo(HashSet()) { it.id }
        val scan = scanMessages(known)
        // Snippets for the threads to rebuild, fetched together: the newest message's text.
        val missing = scan.tallies.keys.filter { it !in known }
        val smsIsNewest = missing.filter { t ->
            val sms = scan.latestSms[t] ?: return@filter false
            sms.first >= (scan.newestMms[t]?.firstOrNull()?.date ?: Long.MIN_VALUE)
        }.toSet()
        val bodies = smsBodies(smsIsNewest.mapNotNull { scan.latestSms[it]?.second })
        val texts = mmsTexts(missing.filter { it !in smsIsNewest }.mapNotNull { scan.newestMms[it]?.firstOrNull()?.id })
        return ThreadIndex.complete(indexed, scan.tallies) { id, tally ->
            // SMS rows name the other party. Only a thread without any needs MMS address rows.
            val fromSms = scan.smsAddresses[id].orEmpty()
            val people = if (fromSms.isNotEmpty()) {
                ThreadIndex.people(fromSms.map { Addressed(outgoing = false, from = it, to = emptyList()) }, ::normalize)
            } else {
                peopleFromMms(id, scan.newestMms[id].orEmpty())
            }
            if (people.isEmpty()) return@complete null
            val snippet = if (id in smsIsNewest) {
                scan.latestSms[id]?.let { bodies[it.second] }
            } else {
                scan.newestMms[id]?.firstOrNull()?.let { texts[it.id] }
            }
            ThreadInfo(id = id, date = tally.latest, count = tally.count, participants = people, snippet = snippet)
        }
    }

    private class CachedThreads(val at: Long, val threads: List<ThreadInfo>)

    @Volatile
    private var cached: CachedThreads? = null

    /**
     * The thread list, kept until a new message is seen (see [messagesSince], which the
     * watcher calls on every provider change) or a minute has passed. Message pages and
     * sync need it on every call.
     */
    @Synchronized
    private fun threads(): List<ThreadInfo> {
        val now = SystemClock.elapsedRealtime()
        cached?.let { if (now - it.at < THREADS_CACHE_MS) return it.threads }
        val all = buildThreads()
        cached = CachedThreads(now, all)
        return all
    }

    internal fun forgetThreads() {
        cached = null
    }

    private fun participantsByThread(): Map<Long, List<String>> = threads().associate { it.id to it.participants }

    override fun threadParticipants(threadId: Long): List<String>? =
        participantsByThread()[threadId] ?: run {
            // Perhaps a thread newer than the kept list.
            forgetThreads()
            participantsByThread()[threadId]
        }

    override fun threadPage(limit: Int, before: ThreadKey?): Page<ThreadSummary, ThreadKey> {
        val all = threads().sortedByDescending { ThreadKey(it.date, it.id) }
        val page = Paging.newestFirst(all, limit, before) { ThreadKey(it.date, it.id) }
        val unread = unreadByThread()
        val items = page.items.map { t ->
            ThreadSummary(
                id = t.id,
                participants = t.participants.ifEmpty { listOf(UNKNOWN) },
                snippet = t.snippet?.take(200),
                date = t.date,
                unreadCount = unread[t.id] ?: 0,
                messageCount = t.count,
            )
        }
        return Page(items, page.next)
    }

    /** Unread inbound messages per thread, in two queries rather than two per thread. */
    private fun unreadByThread(): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        fun add(uri: Uri, selection: String) {
            query(uri, arrayOf("thread_id"), selection, null, null)?.use { c ->
                while (c.moveToNext()) out.merge(c.getLong(0), 1, Int::plus)
            }
        }
        add(Telephony.Sms.CONTENT_URI, "read = 0 AND type = ${RowMapping.SMS_INBOX}")
        add(Telephony.Mms.CONTENT_URI, "read = 0 AND msg_box = ${RowMapping.MMS_INBOX}")
        return out
    }

    // Messages ------------------------------------------------------------

    override fun messagePage(threadId: Long, limit: Int, before: MessageKey?): Page<Message, MessageKey>? {
        val people = threadParticipants(threadId) ?: return null
        val take = limit + PAGE_SLACK
        val smsSel = "thread_id = ?" + if (before != null) " AND date <= ?" else ""
        val smsArgs = listOfNotNull(threadId.toString(), before?.date?.toString()).toTypedArray()
        val sms = querySms(smsSel, smsArgs, "date DESC, _id DESC", take) { people }.mapNotNull { it.second }
        val mmsArgs = listOfNotNull(threadId.toString(), before?.date?.let { (it / 1000).toString() }).toTypedArray()
        val mms = queryMms(smsSel, mmsArgs, "date DESC, _id DESC", take) { people }.mapNotNull { it.second }
        val key = { m: Message -> MessageKey.of(m.date, m.id) }
        return Paging.newestFirst((sms + mms).sortedByDescending(key), limit, before, key)
    }

    override fun messagesSince(cursor: SyncCursor, limit: Int): SyncResult {
        // The watcher asks this every 30 seconds and usually finds nothing. The thread
        // list is only needed, and only rebuilt, when there are new rows to describe.
        val people = lazy {
            forgetThreads()
            participantsByThread()
        }
        val sms = querySms("_id > ?", arrayOf(cursor.sms.toString()), "_id ASC", limit + 1) { people.value[it].orEmpty() }
        val mms = queryMms("_id > ?", arrayOf(cursor.mms.toString()), "_id ASC", limit + 1) { people.value[it].orEmpty() }
        return SyncPaging.assemble(cursor, sms, mms, limit)
    }

    override fun latestCursor(): SyncCursor = SyncCursor(maxId(Telephony.Sms.CONTENT_URI), maxId(Telephony.Mms.CONTENT_URI))

    private fun maxId(uri: Uri): Long =
        query(uri, arrayOf("_id"), null, null, "_id DESC")?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    /** Reads at most [max] rows; the cursor window keeps this cheap without a LIMIT clause. */
    private fun querySms(
        selection: String,
        args: Array<String>,
        order: String,
        max: Int,
        peopleFor: (Long) -> List<String>,
    ): List<Pair<Long, Message?>> =
        query(Telephony.Sms.CONTENT_URI, SMS_COLUMNS, selection, args, order)?.use { c ->
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
                    add(row.id to RowMapping.sms(row, peopleFor(row.threadId), ::normalize))
                }
            }
        }.orEmpty()

    private fun queryMms(
        selection: String,
        args: Array<String>,
        order: String,
        max: Int,
        peopleFor: (Long) -> List<String>,
    ): List<Pair<Long, Message?>> =
        query(Telephony.Mms.CONTENT_URI, MMS_COLUMNS, selection, args, order)?.use { c ->
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
        query(Uri.parse("content://mms/$mmsId/addr"), arrayOf("address", "type"), null, null, null)?.use { c ->
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
        query(MMS_PARTS, arrayOf("_id", "ct", "text", "name", "cl"), "mid = ?", arrayOf(mmsId.toString()), null)?.use { c ->
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
        val ct = query(uri, arrayOf("ct"), null, null, null)?.use { if (it.moveToFirst()) it.getStringOrNull(0) else null }
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
            query(phones, columns, null, null, null)?.use { c ->
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
        private const val REBUILD_MMS_SCAN = 5
        private const val THREADS_CACHE_MS = 60_000L
        private const val MAX_PART_BYTES = 20 * 1024 * 1024
        private const val UNKNOWN = "unknown"
    }
}
