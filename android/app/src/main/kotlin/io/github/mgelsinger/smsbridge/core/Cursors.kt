package io.github.mgelsinger.smsbridge.core

/**
 * Cursor encodings. Clients treat these as opaque strings (PROTOCOL.md), so the
 * formats can change without a protocol bump.
 */

/** Highest provider row id seen per table. Rows with larger ids are new. */
data class SyncCursor(val sms: Long, val mms: Long) {
    fun encode(): String = "s$sms.m$mms"

    companion object {
        private val pattern = Regex("^s(\\d{1,18})\\.m(\\d{1,18})$")
        fun decode(raw: String): SyncCursor? =
            pattern.matchEntire(raw)?.let { SyncCursor(it.groupValues[1].toLong(), it.groupValues[2].toLong()) }
    }
}

/** Threads are ordered newest first by (date, id). */
data class ThreadKey(val date: Long, val id: Long) : Comparable<ThreadKey> {
    override fun compareTo(other: ThreadKey): Int = compareValuesBy(this, other, { it.date }, { it.id })
    fun encode(): String = "t$date.$id"

    companion object {
        private val pattern = Regex("^t(\\d{1,18})\\.(\\d{1,18})$")
        fun decode(raw: String): ThreadKey? =
            pattern.matchEntire(raw)?.let { ThreadKey(it.groupValues[1].toLong(), it.groupValues[2].toLong()) }
    }
}

/** Messages in a thread are ordered newest first by (date, kind, row id). */
data class MessageKey(val date: Long, val kind: String, val rowId: Long) : Comparable<MessageKey> {
    override fun compareTo(other: MessageKey): Int =
        compareValuesBy(this, other, { it.date }, { it.kind }, { it.rowId })

    fun encode(): String = "m$date.$kind.$rowId"

    companion object {
        private val pattern = Regex("^m(\\d{1,18})\\.(sms|mms)\\.(\\d{1,18})$")
        fun decode(raw: String): MessageKey? =
            pattern.matchEntire(raw)?.let {
                MessageKey(it.groupValues[1].toLong(), it.groupValues[2], it.groupValues[3].toLong())
            }

        /** Key for a message id like `sms:1001`. */
        fun of(date: Long, messageId: String): MessageKey {
            val (kind, n) = messageId.split(':', limit = 2)
            return MessageKey(date, kind, n.toLong())
        }
    }
}

data class Page<T, K>(val items: List<T>, val next: K?)

object Paging {
    /**
     * Takes up to [limit] items strictly before [before] from [itemsNewestFirst].
     * The next key is the last item's key when more items remain.
     */
    fun <T, K : Comparable<K>> newestFirst(
        itemsNewestFirst: List<T>,
        limit: Int,
        before: K?,
        key: (T) -> K,
    ): Page<T, K> {
        val remaining = if (before == null) itemsNewestFirst else itemsNewestFirst.filter { key(it) < before }
        val page = remaining.take(limit)
        val next = if (remaining.size > limit) key(page.last()) else null
        return Page(page, next)
    }
}

object SyncPaging {
    /**
     * Builds a sync page from rows above [cursor]: [sms] and [mms] are (row id,
     * mapped message) pairs in id order, each fetched with at most limit + 1 rows.
     * Rows that map to null (drafts) still advance the cursor, so they are not
     * rescanned.
     */
    fun assemble(
        cursor: SyncCursor,
        sms: List<Pair<Long, io.github.mgelsinger.smsbridge.protocol.Message?>>,
        mms: List<Pair<Long, io.github.mgelsinger.smsbridge.protocol.Message?>>,
        limit: Int,
    ): SyncResult {
        val rows = sms.map { Triple("sms", it.first, it.second) } + mms.map { Triple("mms", it.first, it.second) }
        val taken = rows.take(limit)
        var smsMax = cursor.sms
        var mmsMax = cursor.mms
        for ((kind, rowId, _) in taken) {
            if (kind == "sms") smsMax = maxOf(smsMax, rowId) else mmsMax = maxOf(mmsMax, rowId)
        }
        return SyncResult(
            messages = taken.mapNotNull { it.third },
            next = SyncCursor(smsMax, mmsMax),
            hasMore = rows.size > limit,
        )
    }
}
