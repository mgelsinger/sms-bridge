package io.github.mgelsinger.smsbridge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression: on a Pixel 9 Pro Fold with Android 17, the system's thread index listed
 * 30 of 42 conversations to this app. Every thread created since the update was left
 * out, with its messages still readable, so the PC never saw the newest conversations.
 */
class ThreadsTest {
    private val indexed = listOf(
        ThreadInfo(id = 7, date = 5_000, count = 3, participants = listOf("+15855550101"), snippet = "from the index"),
        ThreadInfo(id = 58, date = 9_000, count = 2, participants = listOf("+15855550102"), snippet = null),
    )

    @Test
    fun threadsMissingFromTheIndexAreRebuiltFromTheirMessages() {
        val tallies = ThreadIndex.tally(
            sequenceOf(7L to 4_000L, 7L to 5_000L, 7L to 1_000L, 58L to 9_000L, 58L to 8_000L, 65L to 9_500L, 65L to 2_000L, 77L to 7_000L),
        )
        assertEquals(ThreadTally(count = 2, latest = 9_500), tallies[65])

        val asked = mutableListOf<Long>()
        val all = ThreadIndex.complete(indexed, tallies) { id, tally ->
            asked += id
            ThreadInfo(id, tally.latest, tally.count, listOf("+1585555${id.toString().padStart(4, '0')}"), "rebuilt")
        }

        assertEquals(listOf(65L, 77L), asked.sorted(), "only threads the index does not list are rebuilt")
        assertEquals(listOf(7L, 58L, 65L, 77L), all.map { it.id }.sorted())
        assertEquals(indexed, all.filter { it.id in setOf(7L, 58L) }, "indexed threads are passed through unchanged")
        val rebuilt = all.single { it.id == 65L }
        assertEquals(2, rebuilt.count)
        assertEquals(9_500, rebuilt.date)
    }

    @Test
    fun aThreadThatCannotBeRebuiltIsLeftOutInsteadOfFailingTheList() {
        val tallies = mapOf(7L to ThreadTally(3, 5_000), 65L to ThreadTally(1, 9_500), 66L to ThreadTally(0, 0))
        val all = ThreadIndex.complete(indexed, tallies) { _, _ -> null }
        assertEquals(indexed, all)
    }

    @Test
    fun peopleComeFromSendersOfInboundAndRecipientsOfOutbound() {
        val same: (String) -> String = { it }
        // SMS rows: the single address is the other party, whichever way the text went.
        val sms = List(3) { Addressed(outgoing = false, from = " +15855550101 ", to = emptyList()) }
        assertEquals(listOf("+15855550101"), ThreadIndex.people(sms, same))

        // One-to-one MMS: this phone's own number is the recipient of inbound messages and
        // the placeholder sender of outbound ones, and must not show up as a participant.
        val oneToOne = listOf(
            Addressed(outgoing = false, from = "+15855550101", to = listOf("+15855550999")),
            Addressed(outgoing = true, from = RowMapping.INSERT_ADDRESS_TOKEN, to = listOf("+15855550101")),
        )
        assertEquals(listOf("+15855550101"), ThreadIndex.people(oneToOne, same))

        // Group MMS: the reply this phone sent names everyone else.
        val group = listOf(
            Addressed(outgoing = false, from = "+15855550101", to = listOf("+15855550999", "+15855550102")),
            Addressed(outgoing = true, from = RowMapping.INSERT_ADDRESS_TOKEN, to = listOf("+15855550101", "+15855550102")),
        )
        assertEquals(listOf("+15855550101", "+15855550102"), ThreadIndex.people(group, same))

        assertEquals(emptyList<String>(), ThreadIndex.people(listOf(Addressed(true, RowMapping.INSERT_ADDRESS_TOKEN, listOf(" "))), same))
    }
}
