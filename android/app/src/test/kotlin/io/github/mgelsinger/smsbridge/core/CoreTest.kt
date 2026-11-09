package io.github.mgelsinger.smsbridge.core

import io.github.mgelsinger.smsbridge.protocol.Direction
import io.github.mgelsinger.smsbridge.protocol.MessageStatus
import io.github.mgelsinger.smsbridge.protocol.MmsPart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CursorsTest {
    @Test
    fun cursorsRoundTripAndRejectGarbage() {
        assertEquals(SyncCursor(1007, 202), SyncCursor.decode(SyncCursor(1007, 202).encode()))
        assertEquals(ThreadKey(1_762_610_282_000, 11), ThreadKey.decode(ThreadKey(1_762_610_282_000, 11).encode()))
        assertEquals(MessageKey(5, "mms", 9), MessageKey.decode(MessageKey(5, "mms", 9).encode()))
        for (bad in listOf("", "s1", "s1.m", "t:2", "m1.xms.3", "s-1.m2", "s1.m2;drop")) {
            assertNull(SyncCursor.decode(bad), bad)
            assertNull(ThreadKey.decode(bad), bad)
            assertNull(MessageKey.decode(bad), bad)
        }
    }

    @Test
    fun pagingWalksEveryItemExactlyOnce() {
        val items = (20 downTo 1).map { ThreadKey(it / 2L, it.toLong()) } // ties on date are broken by id
        val seen = mutableListOf<ThreadKey>()
        var before: ThreadKey? = null
        do {
            val page = Paging.newestFirst(items, 3, before) { it }
            seen += page.items
            before = page.next
        } while (before != null)
        assertEquals(items, seen)
    }

    @Test
    fun syncAdvancesPastDraftsAndReportsMore() {
        val m = { id: String -> RowMapping.sms(SmsRow(id.substringAfter(':').toLong(), 1, "+1555", "x", 1, 1, true, -1), emptyList()) { it }!! }
        val result = SyncPaging.assemble(
            SyncCursor(10, 5),
            sms = listOf(11L to m("sms:11"), 12L to null, 13L to m("sms:13")),
            mms = listOf(6L to null),
            limit = 3,
        )
        assertEquals(listOf("sms:11", "sms:13"), result.messages.map { it.id })
        assertEquals(SyncCursor(13, 5), result.next)
        assertTrue(result.hasMore)
        val rest = SyncPaging.assemble(result.next, emptyList(), listOf(6L to null), 3)
        assertEquals(SyncCursor(13, 6), rest.next)
        assertEquals(false, rest.hasMore)
    }
}

class RowMappingTest {
    private val norm = { raw: String -> if (raw.startsWith("+")) raw else "+1$raw" }
    private fun sms(type: Int, status: Int = -1, address: String? = "5855550101") =
        RowMapping.sms(SmsRow(1001, 11, address, "hi", 1_700_000_000_123, type, false, status), emptyList(), norm)

    @Test
    fun smsDirectionAndStatus() {
        assertEquals(Direction.IN, sms(RowMapping.SMS_INBOX)!!.direction)
        assertNull(sms(RowMapping.SMS_INBOX)!!.status)
        assertEquals(MessageStatus.SENT, sms(RowMapping.SMS_SENT)!!.status)
        assertEquals(MessageStatus.DELIVERED, sms(RowMapping.SMS_SENT, RowMapping.STATUS_COMPLETE)!!.status)
        assertEquals(MessageStatus.FAILED, sms(RowMapping.SMS_SENT, RowMapping.STATUS_FAILED)!!.status)
        assertEquals(MessageStatus.PENDING, sms(RowMapping.SMS_OUTBOX)!!.status)
        assertEquals(MessageStatus.PENDING, sms(RowMapping.SMS_QUEUED)!!.status)
        assertEquals(MessageStatus.FAILED, sms(RowMapping.SMS_FAILED)!!.status)
        assertEquals(Direction.OUT, sms(RowMapping.SMS_FAILED)!!.direction)
    }

    @Test
    fun smsDraftsAndAddresslessRowsAreSkipped() {
        assertNull(sms(RowMapping.SMS_DRAFT))
        assertNull(sms(RowMapping.SMS_INBOX, address = null))
        assertNull(sms(RowMapping.SMS_INBOX, address = "  "))
    }

    @Test
    fun smsAddressIsNormalizedAndIdIsTyped() {
        val m = sms(RowMapping.SMS_INBOX)!!
        assertEquals("+15855550101", m.address)
        assertEquals(listOf("+15855550101"), m.participants)
        assertEquals("sms:1001", m.id)
        assertEquals(1_700_000_000_123, m.date)
    }

    @Test
    fun mmsDatesAreConvertedFromSecondsAndSelfTokenIsSkipped() {
        val part = MmsPart(3001, "image/png", null, "a.png")
        val sent = RowMapping.mms(
            MmsRow(201, 13, 1_700_000_000, RowMapping.MMS_SENT, true, RowMapping.INSERT_ADDRESS_TOKEN, listOf("+13155550103"), " ", listOf(part)),
            listOf("+13155550103", "+15855550101"),
            norm,
        )!!
        assertEquals(1_700_000_000_000, sent.date)
        assertEquals("+13155550103", sent.address)
        assertEquals(Direction.OUT, sent.direction)
        assertNull(sent.body, "blank text parts become a null body")
        assertEquals(listOf(part), sent.parts)
        assertEquals(2, sent.participants.size)

        val received = RowMapping.mms(
            MmsRow(202, 13, 1_700_000_001, RowMapping.MMS_INBOX, false, "+15855550101", listOf("+19995550000"), "look", emptyList()),
            emptyList(),
            norm,
        )!!
        assertEquals("+15855550101", received.address)
        assertEquals(Direction.IN, received.direction)
        assertNull(RowMapping.mms(MmsRow(203, 13, 1, RowMapping.MMS_DRAFTS, true, null, emptyList(), null, emptyList()), emptyList(), norm))
    }

    @Test
    fun attachmentFilterDropsTextAndSmil() {
        assertTrue(RowMapping.isAttachment("image/jpeg"))
        assertEquals(false, RowMapping.isAttachment("text/plain"))
        assertEquals(false, RowMapping.isAttachment("application/smil"))
    }
}

class PairingManagerTest {
    private var clock = 1_000_000L
    private val store = InMemoryClientStore()
    private val pairing = PairingManager(store, now = { clock })

    @Test
    fun codeWorksOnceAndExpires() {
        val s = pairing.startSession()
        assertTrue(Regex("^\\d{6}$").matches(s.code))
        assertEquals(PairingManager.CodeCheck.VALID, pairing.checkCode(s.code))
        assertEquals(PairingManager.CodeCheck.INVALID, pairing.checkCode(s.code), "single use")
        val late = pairing.startSession()
        clock += PairingManager.SESSION_TTL_MS
        assertEquals(PairingManager.CodeCheck.INVALID, pairing.checkCode(late.code), "expired")
    }

    @Test
    fun fiveFailuresLockPairingForTenMinutes() {
        val s = pairing.startSession()
        val wrong = if (s.code == "000000") "000001" else "000000"
        repeat(5) { assertEquals(PairingManager.CodeCheck.INVALID, pairing.checkCode(wrong)) }
        assertEquals(PairingManager.CodeCheck.LOCKED, pairing.checkCode(s.code), "even the right code is refused while locked")
        clock += PairingManager.LOCKOUT_MS
        val fresh = pairing.startSession()
        assertEquals(PairingManager.CodeCheck.VALID, pairing.checkCode(fresh.code))
    }

    @Test
    fun failuresOutsideTheWindowDoNotCount() {
        pairing.startSession()
        repeat(4) { pairing.checkCode("999999") }
        clock += PairingManager.FAILURE_WINDOW_MS
        repeat(4) { pairing.checkCode("999999") }
        assertNotEquals(PairingManager.CodeCheck.LOCKED, pairing.checkCode("999999"))
    }

    @Test
    fun tokensAreStoredOnlyAsHashesAndCanBeRevoked() {
        val (client, token) = pairing.issueToken("MIDGAR")
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(token))
        assertEquals(sha256Hex(token.toByteArray()), store.all().single().tokenHash)
        assertTrue(store.all().none { it.tokenHash.contains(token) })
        assertEquals(client.id, pairing.authenticate("Bearer $token")?.id)
        assertNull(pairing.authenticate("Bearer ${"A".repeat(43)}"))
        assertNull(pairing.authenticate(token), "the Bearer prefix is required")
        assertNull(pairing.authenticate(null))
        pairing.revoke(client.id)
        assertNull(pairing.authenticate("Bearer $token"))
    }
}
