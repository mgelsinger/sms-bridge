package io.github.mgelsinger.smsbridge.core

import io.github.mgelsinger.smsbridge.protocol.Call
import io.github.mgelsinger.smsbridge.protocol.CallAbilities
import io.github.mgelsinger.smsbridge.protocol.CallAction
import io.github.mgelsinger.smsbridge.protocol.CallActionFrame
import io.github.mgelsinger.smsbridge.protocol.CallKind
import io.github.mgelsinger.smsbridge.protocol.CallResultFrame
import io.github.mgelsinger.smsbridge.protocol.CallResultStatus
import io.github.mgelsinger.smsbridge.protocol.CallStateFrame
import io.github.mgelsinger.smsbridge.protocol.CallsPage
import io.github.mgelsinger.smsbridge.protocol.DialFrame
import io.github.mgelsinger.smsbridge.protocol.Direction
import io.github.mgelsinger.smsbridge.protocol.ErrorBody
import io.github.mgelsinger.smsbridge.protocol.ErrorCode
import io.github.mgelsinger.smsbridge.protocol.ErrorFrame
import io.github.mgelsinger.smsbridge.protocol.LineState
import io.github.mgelsinger.smsbridge.protocol.ProtocolJson
import io.github.mgelsinger.smsbridge.protocol.StatusResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A phone that records what it was asked to do. */
class FakeTelephone : Telephone {
    var allowed = CallAbilities(history = true, dial = true, answer = true, end = true)
    val log = mutableListOf<Call>()
    val dialed = mutableListOf<String>()
    var answers = 0
    var ends = 0
    var dialError: String? = null

    override fun abilities() = allowed
    override fun callsSince(afterId: Long, limit: Int): List<Call>? =
        if (allowed.history) log.filter { CallRows.rowId(it) > afterId }.sortedBy(CallRows::rowId).take(limit) else null
    override fun latestCallId(): Long? = if (allowed.history) log.maxOfOrNull(CallRows::rowId) ?: 0 else null
    override fun isEmergency(number: String) = number in setOf("911", "112")
    override fun dial(number: String): String? = dialError ?: null.also { dialed += number }
    override fun answer(): String? = null.also { answers++ }
    override fun end(): String? = null.also { ends++ }
}

private const val ALEX = "+15855550101"

private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

class LineTrackerTest {
    private var clock = 1_000L
    private val line = LineTracker { clock }

    private fun shape(f: CallStateFrame?) = f?.let { Triple(it.state, it.direction, it.number) }

    @Test
    fun anInboundCallRingsThenIsAnsweredThenEnds() {
        assertEquals(Triple(LineState.RINGING, Direction.IN, ALEX), shape(line.report(Hook.RINGING, ALEX)))
        clock = 5_000
        val answered = line.report(Hook.OFF_HOOK, null)
        assertEquals(Triple(LineState.ACTIVE, Direction.IN, ALEX), shape(answered), "the caller is kept once answered")
        assertEquals(5_000L, answered!!.since)
        assertEquals(Triple(LineState.IDLE, null, null), shape(line.report(Hook.IDLE, null)))
    }

    /** Android reports a ringing call twice, once without the number, in either order. */
    @Test
    fun theSecondRingingReportOnlyAddsTheNumber() {
        assertEquals(Triple(LineState.RINGING, Direction.IN, null), shape(line.report(Hook.RINGING, null)))
        val started = line.current().since
        clock += 40
        val withNumber = line.report(Hook.RINGING, ALEX)
        assertEquals(Triple(LineState.RINGING, Direction.IN, ALEX), shape(withNumber))
        assertEquals(started, withNumber!!.since, "it is the same ring")
        assertNull(line.report(Hook.RINGING, null), "the report without a number changes nothing")
        assertNull(line.report(Hook.RINGING, ALEX))
        assertEquals(ALEX, line.current().number)
    }

    @Test
    fun aCallPlacedOnThePhoneItselfIsOutboundWithNoNumber() {
        assertEquals(Triple(LineState.ACTIVE, Direction.OUT, null), shape(line.report(Hook.OFF_HOOK, null)))
        assertNull(line.report(Hook.OFF_HOOK, null), "still the same call")
    }

    @Test
    fun aCallTheClientAskedForCarriesItsNumberButOnlyTheNextOne() {
        line.expectOutbound(ALEX)
        clock += 2_000
        assertEquals(Triple(LineState.ACTIVE, Direction.OUT, ALEX), shape(line.report(Hook.OFF_HOOK, null)))
        line.report(Hook.IDLE, null)
        assertEquals(Triple(LineState.ACTIVE, Direction.OUT, null), shape(line.report(Hook.OFF_HOOK, null)))
    }

    @Test
    fun aDialThatNeverStartedDoesNotNameALaterCall() {
        line.expectOutbound(ALEX)
        clock += 31_000
        assertEquals(Triple(LineState.ACTIVE, Direction.OUT, null), shape(line.report(Hook.OFF_HOOK, null)))
    }

    @Test
    fun aSecondCallDuringACallRingsAndThenTheLineIsActiveAgain() {
        line.report(Hook.OFF_HOOK, null)
        assertEquals(Triple(LineState.RINGING, Direction.IN, ALEX), shape(line.report(Hook.RINGING, ALEX)))
        assertEquals(Triple(LineState.ACTIVE, Direction.IN, ALEX), shape(line.report(Hook.OFF_HOOK, null)))
    }

    @Test
    fun idleWhileIdleIsNotAChange() {
        assertNull(line.report(Hook.IDLE, null))
        assertEquals(LineState.IDLE, line.current().state)
    }
}

class CallDeskTest {
    private var clock = 1_000L
    private val phone = FakeTelephone()
    private val line = LineTracker { clock }
    private val logs = mutableListOf<String>()
    private val desk = CallDesk(phone, line, now = { clock }, log = { logs += it })

    private fun dial(n: Int, to: String) = desk.dial(DialFrame(clientId = uuid(n), to = to))
    private fun act(n: Int, action: CallAction) = desk.act(CallActionFrame(clientId = uuid(n), action = action))
    private fun shape(r: CallResultFrame) = r.status to r.error

    @Test
    fun dialHandsTheNumberToThePhoneOnceAndTheLineThenNamesIt() {
        assertEquals(CallResultStatus.OK to null, shape(dial(1, "+1 (585) 555-0101")))
        assertEquals(listOf("+15855550101"), phone.dialed, "separators are dropped before dialing")
        assertEquals("+1 (585) 555-0101", line.report(Hook.OFF_HOOK, null)!!.number)
    }

    /** The rule that matters most: one request, one call, however often the frame arrives. */
    @Test
    fun aRepeatedDialIsAnsweredAsBeforeAndNeverDialedAgain() {
        dial(1, ALEX)
        line.report(Hook.OFF_HOOK, null)
        line.report(Hook.IDLE, null)
        assertEquals(CallResultStatus.OK to null, shape(dial(1, ALEX)))
        assertEquals(1, phone.dialed.size)
        clock += 23 * 60 * 60 * 1000L
        dial(1, ALEX)
        assertEquals(1, phone.dialed.size, "still remembered within the day")
    }

    @Test
    fun emergencyNumbersAreNeverDialedFromAClient() {
        assertEquals(CallResultStatus.FAILED to "emergency numbers are dialed on the phone", shape(dial(1, "911")))
        assertEquals(CallResultStatus.FAILED to "emergency numbers are dialed on the phone", shape(dial(2, "1-1-2")))
        assertTrue(phone.dialed.isEmpty())
    }

    @Test
    fun dialIsRefusedForNonNumbersABusyLineAndAMissingPermission() {
        assertEquals("that is not a number the phone can dial", dial(1, "call mom").error)
        assertEquals("that is not a number the phone can dial", dial(2, "tel:+15855550101").error)
        line.report(Hook.RINGING, ALEX)
        assertEquals("a call is already in progress", dial(3, ALEX).error)
        line.report(Hook.IDLE, null)
        phone.allowed = phone.allowed.copy(dial = false)
        assertEquals("the phone app may not place calls", dial(4, ALEX).error)
        assertTrue(phone.dialed.isEmpty())
    }

    @Test
    fun aDialThePhoneRefusesDoesNotNameTheNextCall() {
        phone.dialError = "the phone could not do that (IllegalStateException)"
        assertEquals(CallResultStatus.FAILED, dial(1, ALEX).status)
        assertNull(line.report(Hook.OFF_HOOK, null)!!.number)
    }

    @Test
    fun answerNeedsARingingCallAndEndNeedsACall() {
        assertEquals("no call is ringing", act(1, CallAction.ANSWER).error)
        assertEquals("there is no call to end", act(2, CallAction.END).error)
        line.report(Hook.RINGING, ALEX)
        assertEquals(CallResultStatus.OK, act(3, CallAction.ANSWER).status)
        line.report(Hook.OFF_HOOK, null)
        assertEquals("no call is ringing", act(4, CallAction.ANSWER).error)
        assertEquals(CallResultStatus.OK, act(5, CallAction.END).status)
        assertEquals(1 to 1, phone.answers to phone.ends)
        assertEquals(CallResultStatus.OK, act(5, CallAction.END).status)
        assertEquals(1, phone.ends, "a repeated request does not hang up the next call")
    }

    @Test
    fun endDeclinesARingingCall() {
        line.report(Hook.RINGING, null)
        assertEquals(CallResultStatus.OK, act(1, CallAction.END).status)
        assertEquals(1, phone.ends)
    }

    @Test
    fun withoutThePermissionAnswerAndEndSaySo() {
        phone.allowed = phone.allowed.copy(answer = false, end = false)
        line.report(Hook.RINGING, ALEX)
        assertEquals("the phone app may not answer calls", act(1, CallAction.ANSWER).error)
        assertEquals("the phone app may not end calls", act(2, CallAction.END).error)
    }

    @Test
    fun numbersNeverReachTheLog() {
        dial(1, ALEX)
        dial(2, "911")
        dial(3, "+15855550199 ext")
        assertFalse(logs.any { line -> line.any(Char::isDigit) }, logs.toString())
    }

    @Test
    fun historyPagesOldestFirstAndTheCursorStaysPutWhenNothingIsNew() {
        phone.log += (1..5).map { Call("call:$it", ALEX, CallKind.IN, it * 1000L, 10) }
        val first = desk.page(null, 2)!!
        assertEquals(listOf("call:1", "call:2"), first.calls.map { it.id })
        assertTrue(first.hasMore)
        val rest = desk.page(CallCursor.decode(first.nextCursor), 10)!!
        assertEquals(listOf("call:3", "call:4", "call:5"), rest.calls.map { it.id })
        assertFalse(rest.hasMore)
        val none = desk.page(CallCursor.decode(rest.nextCursor), 10)!!
        assertTrue(none.calls.isEmpty())
        assertEquals(rest.nextCursor, none.nextCursor)
        phone.allowed = phone.allowed.copy(history = false)
        assertNull(desk.page(null, 10))
    }

    @Test
    fun callLogTypesMapToKinds() {
        assertEquals(
            listOf(CallKind.IN, CallKind.OUT, CallKind.MISSED, CallKind.VOICEMAIL, CallKind.REJECTED, CallKind.BLOCKED, CallKind.IN, CallKind.IN),
            (1..8).map(CallRows::kind),
        )
        assertEquals(CallCursor(415), CallCursor.decode("c415"))
        assertNull(CallCursor.decode("s1.m2"))
    }
}

class BridgeApiCallsTest {
    private val phone = FakeTelephone()
    private val line = LineTracker()
    private val pairing = PairingManager(InMemoryClientStore())
    private val api = BridgeApi(FixtureRepository(), pairing, confirmPairing = { true }, calls = CallDesk(phone, line))
    private val client = pairing.issueToken("TEST-PC").first

    private fun get(path: String, query: Map<String, String> = emptyMap()) = runBlocking { api.handle("GET", path, query, null) }
    private fun frame(json: String) = runBlocking { api.onFrame(client, json) }.frames

    @Test
    fun statusSaysWhatThePhoneCanDoAndTheHistoryIsServed() {
        phone.allowed = phone.allowed.copy(answer = false, end = false)
        val status = ProtocolJson.decodeFromString(StatusResponse.serializer(), get("/status").body.decodeToString())
        assertEquals(CallAbilities(history = true, dial = true, answer = false, end = false), status.calls)

        phone.log += Call("call:7", null, CallKind.MISSED, 5000, 0)
        val page = ProtocolJson.decodeFromString(CallsPage.serializer(), get("/calls").body.decodeToString())
        assertEquals(listOf("call:7"), page.calls.map { it.id })
        assertEquals("c7", page.nextCursor)
        assertEquals(400, get("/calls", mapOf("cursor" to "t1.2")).status)
        assertEquals(400, get("/calls", mapOf("limit" to "501")).status)
    }

    @Test
    fun theHistoryIsRefusedWithoutThePermission() {
        phone.allowed = phone.allowed.copy(history = false)
        val refused = get("/calls")
        assertEquals(403, refused.status)
        assertEquals(ErrorCode.NOT_PERMITTED, ProtocolJson.decodeFromString(ErrorBody.serializer(), refused.body.decodeToString()).error.code)
    }

    @Test
    fun theLineIsReportedRightAfterHello() {
        line.report(Hook.RINGING, ALEX)
        val frames = api.afterHello()
        assertEquals(LineState.RINGING, (frames.single() as CallStateFrame).state)
    }

    @Test
    fun dialAndCallActionFramesAreAnsweredWithOneResult() {
        val ok = frame("""{"protocol_version":1,"type":"dial","client_id":"${uuid(1)}","to":"$ALEX"}""")
        assertEquals(CallResultStatus.OK, (ok.single() as CallResultFrame).status)
        assertEquals(listOf(ALEX), phone.dialed)
        val idle = frame("""{"protocol_version":1,"type":"call_action","client_id":"${uuid(2)}","action":"end"}""")
        assertEquals(CallResultStatus.FAILED, (idle.single() as CallResultFrame).status)
    }

    @Test
    fun malformedCallFramesAreErrorsAndNothingIsDialed() {
        val noNumber = frame("""{"protocol_version":1,"type":"dial","client_id":"${uuid(1)}"}""")
        assertEquals(ErrorCode.BAD_REQUEST, (noNumber.single() as ErrorFrame).code)
        val badId = frame("""{"protocol_version":1,"type":"dial","client_id":"nope","to":"$ALEX"}""")
        assertEquals(ErrorCode.BAD_REQUEST, (badId.single() as ErrorFrame).code)
        val badAction = frame("""{"protocol_version":1,"type":"call_action","client_id":"${uuid(2)}","action":"silence"}""")
        assertEquals(ErrorCode.BAD_REQUEST, (badAction.single() as ErrorFrame).code)
        assertTrue(phone.dialed.isEmpty())
    }

    /** A build without calls behaves like a phone from before them. */
    @Test
    fun withoutCallsThePhoneSaysNothingAboutThem() {
        val plain = BridgeApi(FixtureRepository(), pairing, confirmPairing = { true })
        val status = runBlocking { plain.handle("GET", "/status", emptyMap(), null) }
        assertFalse("\"calls\"" in status.body.decodeToString())
        assertEquals(404, runBlocking { plain.handle("GET", "/calls", emptyMap(), null) }.status)
        assertTrue(plain.afterHello().isEmpty())
        val reply = runBlocking { plain.onFrame(client, """{"protocol_version":1,"type":"dial","client_id":"${uuid(1)}","to":"$ALEX"}""") }
        assertTrue(reply.frames.isEmpty(), "an unknown frame type is ignored")
    }
}
