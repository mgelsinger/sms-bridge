package io.github.mgelsinger.smsbridge.core

/** One conversation, from whichever source knows about it. */
data class ThreadInfo(
    val id: Long,
    val date: Long,
    val count: Int,
    val participants: List<String>,
    val snippet: String?,
)

/** What the message tables themselves hold for a thread: how many messages, and when the newest one is from. */
data class ThreadTally(val count: Int, val latest: Long)

/** Who a stored message came from and went to. For SMS rows the single address is the other party. */
data class Addressed(val outgoing: Boolean, val from: String?, val to: List<String>)

object ThreadIndex {
    /**
     * The system's thread index is not the whole truth. Android 17 leaves threads created
     * since the update out of it (and out of the canonical address table) for apps that
     * are not the default SMS app, although their messages stay readable. Every thread
     * that has messages but no index entry is rebuilt from its messages with [rebuild].
     */
    fun complete(
        indexed: List<ThreadInfo>,
        tallies: Map<Long, ThreadTally>,
        rebuild: (id: Long, tally: ThreadTally) -> ThreadInfo?,
    ): List<ThreadInfo> {
        val known = indexed.mapTo(HashSet()) { it.id }
        val missing = tallies.entries.filter { it.key !in known && it.value.count > 0 }
        return indexed + missing.mapNotNull { rebuild(it.key, it.value) }
    }

    /** Folds one (thread id, time in milliseconds) pair per message into tallies. */
    fun tally(messages: Sequence<Pair<Long, Long>>): Map<Long, ThreadTally> {
        val out = HashMap<Long, ThreadTally>()
        for ((thread, time) in messages) {
            val seen = out[thread]
            out[thread] = if (seen == null) ThreadTally(1, time) else ThreadTally(seen.count + 1, maxOf(seen.latest, time))
        }
        return out
    }

    /**
     * The other people in a thread, from its messages alone: whoever wrote to this phone
     * and whomever this phone wrote to. This phone's own number is never needed: it is
     * only ever the recipient of inbound messages and the sender of outbound ones. A group
     * member who never wrote, in a group this phone never wrote to, is not found.
     */
    fun people(messages: List<Addressed>, normalize: (String) -> String): List<String> =
        messages
            .flatMap { if (it.outgoing) it.to else listOfNotNull(it.from) }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != RowMapping.INSERT_ADDRESS_TOKEN }
            .map(normalize)
            .distinct()
}
