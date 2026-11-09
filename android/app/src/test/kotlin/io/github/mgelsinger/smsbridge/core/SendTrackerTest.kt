package io.github.mgelsinger.smsbridge.core

import io.github.mgelsinger.smsbridge.protocol.Direction
import io.github.mgelsinger.smsbridge.protocol.Message
import io.github.mgelsinger.smsbridge.protocol.MessageKind
import io.github.mgelsinger.smsbridge.protocol.MessageStatus
import io.github.mgelsinger.smsbridge.protocol.SendStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SendTrackerTest {
    private var clock = 1_000_000L
    private val tracker = SendTracker { clock }
    private val cid = "3f0e6c1a-9b2d-4c7e-8a51-2d6f0b9e4c13"

    private fun row(id: String = "sms:900", address: String = "5855550101", body: String = "hi", direction: Direction = Direction.OUT) = Message(
        id = id, threadId = 11, kind = MessageKind.SMS, direction = direction, address = address, participants = listOf(address),
        body = body, parts = emptyList(), date = clock, read = true, status = MessageStatus.SENT, clientId = null,
    )

    @Test
    fun multipartSendReportsSentOnlyAfterEveryPart() {
        assertEquals(SendStatus.QUEUED, tracker.begin(cid, "+15855550101", "hi", parts = 3).status)
        assertNull(tracker.partSent(cid, true))
        assertNull(tracker.partSent(cid, true))
        assertEquals(SendStatus.SENT, tracker.partSent(cid, true)?.status)
        assertNull(tracker.partDelivered(cid, true))
        assertNull(tracker.partDelivered(cid, true))
        assertEquals(SendStatus.DELIVERED, tracker.partDelivered(cid, true)?.status)
        assertNull(tracker.partDelivered(cid, true), "no repeats after delivered")
    }

    @Test
    fun theFirstFailedPartFailsTheSendOnce() {
        tracker.begin(cid, "+15855550101", "hi", parts = 2)
        val failed = tracker.partSent(cid, false, "RESULT_ERROR_NO_SERVICE")
        assertEquals(SendStatus.FAILED, failed?.status)
        assertEquals("RESULT_ERROR_NO_SERVICE", failed?.error)
        assertNull(tracker.partSent(cid, true))
        assertNull(tracker.partDelivered(cid, true))
    }

    @Test
    fun knownClientIdsAreRememberedFor24Hours() {
        tracker.begin(cid, "+15855550101", "hi", parts = 1)
        tracker.partSent(cid, true)
        assertEquals(SendStatus.SENT, tracker.known(cid)?.status)
        clock += SendTracker.MEMORY_MS - 1
        assertEquals(SendStatus.SENT, tracker.known(cid)?.status)
        clock += 2
        assertNull(tracker.known(cid))
    }

    @Test
    fun theStoredCopyIsClaimedByNumberAndBody() {
        tracker.begin(cid, "+15855550101", "hi", parts = 1)
        val (unrelated, _) = tracker.claim(row(body = "something else"))
        assertNull(unrelated.clientId)
        val (incoming, _) = tracker.claim(row(direction = Direction.IN))
        assertNull(incoming.clientId)

        val (claimed, update) = tracker.claim(row())
        assertEquals(cid, claimed.clientId)
        assertNull(update, "still queued: the id rides on the next result")
        assertEquals("sms:900", tracker.partSent(cid, true)?.messageId)

        val (second, _) = tracker.claim(row(id = "sms:901"))
        assertNull(second.clientId, "each send claims one row")
    }

    @Test
    fun aRowStoredAfterSentProducesAResultWithTheMessageId() {
        tracker.begin(cid, "+15855550101", "hi", parts = 1)
        tracker.partSent(cid, true)
        val (_, update) = tracker.claim(row())
        assertEquals(SendStatus.SENT, update?.status)
        assertEquals("sms:900", update?.messageId)
    }

    @Test
    fun numbersCompareByTheirLastTenDigits() {
        assertTrue(SendTracker.sameNumber("+1 (585) 555-0101", "5855550101"))
        assertTrue(!SendTracker.sameNumber("+15855550101", "+15855550102"))
        assertTrue(SendTracker.sameNumber("AMAZON", "amazon"))
    }
}
