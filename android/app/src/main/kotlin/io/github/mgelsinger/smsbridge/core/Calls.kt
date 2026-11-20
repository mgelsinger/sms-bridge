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
import io.github.mgelsinger.smsbridge.protocol.LineState

/**
 * The phone's telephony as the bridge needs it. The Android implementation asks
 * the system; the app is not the phone's dialer, so this is all it can do.
 */
interface Telephone {
    /** What the app may do right now, given the permissions it holds. */
    fun abilities(): CallAbilities

    /** History entries with row ids above [afterId], oldest first. Null when the call log may not be read. */
    fun callsSince(afterId: Long, limit: Int): List<Call>?

    /** The newest history row id, 0 when there is none, null when the call log may not be read. */
    fun latestCallId(): Long?

    fun isEmergency(number: String): Boolean

    /** Each of these returns null when the phone took the request, otherwise why it did not. */
    fun dial(number: String): String?
    fun answer(): String?
    fun end(): String?
}

/** The highest call log row id a client has seen. */
data class CallCursor(val id: Long) {
    fun encode(): String = "c$id"

    companion object {
        private val pattern = Regex("^c(\\d{1,18})$")
        fun decode(raw: String): CallCursor? = pattern.matchEntire(raw)?.let { CallCursor(it.groupValues[1].toLong()) }
    }
}

object CallRows {
    /** Row id of a history entry id like `call:412`. */
    fun rowId(call: Call): Long = call.id.substringAfter(':').toLong()

    /** Maps `CallLog.Calls.TYPE`. Values Android may add later read as an answered inbound call. */
    fun kind(type: Int): CallKind = when (type) {
        2 -> CallKind.OUT
        3 -> CallKind.MISSED
        4 -> CallKind.VOICEMAIL
        5 -> CallKind.REJECTED
        6 -> CallKind.BLOCKED
        else -> CallKind.IN // 1 incoming, 7 answered on another device
    }
}

/** What the telephony service says about the line. */
enum class Hook { IDLE, RINGING, OFF_HOOK }

/**
 * Turns what the telephony service reports into the protocol's line. The service
 * says only idle, ringing, or off the hook, and who is ringing; the rest is
 * worked out here: off the hook after ringing is that call answered, off the hook
 * from idle is an outbound call.
 */
class LineTracker(private val now: () -> Long = System::currentTimeMillis) {
    private var state = LineState.IDLE
    private var direction: Direction? = null
    private var number: String? = null
    private var since = now()
    private var expected: Pair<String, Long>? = null

    @Synchronized
    fun current(): CallStateFrame = CallStateFrame(state = state, direction = direction, number = number, since = since)

    /** A client asked the phone to dial [number]: the next outbound call is that one. */
    @Synchronized
    fun expectOutbound(number: String) {
        expected = number to now()
    }

    @Synchronized
    fun forgetExpected() {
        expected = null
    }

    /**
     * Takes one report from the telephony service. Returns the frame to publish
     * when the line changed, null when it did not. Android reports a ringing call
     * twice, with and without the number, in either order.
     */
    @Synchronized
    fun report(hook: Hook, reportedNumber: String?): CallStateFrame? {
        val caller = reportedNumber?.takeIf { it.isNotBlank() }
        when (hook) {
            Hook.IDLE -> {
                expected = null
                if (state == LineState.IDLE) return null
                set(LineState.IDLE, null, null)
            }
            Hook.RINGING -> when {
                state != LineState.RINGING -> set(LineState.RINGING, Direction.IN, caller)
                caller != null && number == null -> number = caller
                else -> return null
            }
            Hook.OFF_HOOK -> when (state) {
                LineState.ACTIVE -> return null
                LineState.RINGING -> set(LineState.ACTIVE, Direction.IN, number)
                LineState.IDLE -> {
                    val dialed = expected?.takeIf { now() - it.second <= EXPECT_MS }?.first
                    expected = null
                    set(LineState.ACTIVE, Direction.OUT, dialed)
                }
            }
        }
        return current()
    }

    private fun set(newState: LineState, newDirection: Direction?, newNumber: String?) {
        state = newState
        direction = newDirection
        number = newNumber
        since = now()
    }

    private companion object {
        /** How long after a client's dial an outbound call is taken to be that dial. */
        const val EXPECT_MS = 30_000L
    }
}

/**
 * Answers a client's call frames and serves the history. Each `client_id` is
 * acted on once: a repeat gets the first answer and the phone does nothing more.
 */
class CallDesk(
    private val phone: Telephone,
    private val line: LineTracker,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val answered = LinkedHashMap<String, Pair<CallResultFrame, Long>>()

    fun abilities(): CallAbilities = phone.abilities()

    fun lineFrame(): CallStateFrame = line.current()

    /** A page of history after [cursor], oldest first. Null when the call log may not be read. */
    fun page(cursor: CallCursor?, limit: Int): CallsPage? {
        val from = cursor?.id ?: 0
        val rows = phone.callsSince(from, limit + 1) ?: return null
        val page = rows.take(limit)
        return CallsPage(
            calls = page,
            nextCursor = CallCursor(page.lastOrNull()?.let(CallRows::rowId) ?: from).encode(),
            hasMore = rows.size > limit,
        )
    }

    /** Null when the frame is well formed. Mirrors protocol/schema/ws-dial.schema.json. */
    fun problem(frame: DialFrame): String? = when {
        !SendValidation.UUID_V4.matches(frame.clientId) -> "client_id must be a lowercase UUID v4"
        frame.to.isBlank() || frame.to.length > 64 -> "to must be 1-64 characters"
        else -> null
    }

    fun problem(frame: CallActionFrame): String? =
        if (SendValidation.UUID_V4.matches(frame.clientId)) null else "client_id must be a lowercase UUID v4"

    @Synchronized
    fun dial(frame: DialFrame): CallResultFrame = once(frame.clientId) {
        val number = frame.to.filterNot { it in SEPARATORS }
        val refusal = when {
            !phone.abilities().dial -> "the phone app may not place calls"
            !DIALABLE.matches(number) -> "that is not a number the phone can dial"
            phone.isEmergency(number) -> "emergency numbers are dialed on the phone"
            line.current().state != LineState.IDLE -> "a call is already in progress"
            else -> null
        }
        if (refusal != null) return@once refusal
        line.expectOutbound(frame.to)
        phone.dial(number).also { if (it != null) line.forgetExpected() }
    }.also { log(if (it.status == CallResultStatus.OK) "dial handed to the phone" else "dial refused: ${it.error}") }

    @Synchronized
    fun act(frame: CallActionFrame): CallResultFrame = once(frame.clientId) {
        val abilities = phone.abilities()
        val state = line.current().state
        when (frame.action) {
            CallAction.ANSWER -> when {
                !abilities.answer -> "the phone app may not answer calls"
                state != LineState.RINGING -> "no call is ringing"
                !stillRingingAfterSettling() -> "no call is ringing"
                else -> phone.answer()
            }
            CallAction.END -> when {
                !abilities.end -> "the phone app may not end calls"
                state == LineState.IDLE -> "there is no call to end"
                state == LineState.RINGING && !stillRingingAfterSettling() -> "there is no call to end"
                else -> phone.end()
            }
        }
    }.also {
        val what = frame.action.name.lowercase()
        log(if (it.status == CallResultStatus.OK) "$what handed to the phone" else "$what refused: ${it.error}")
    }

    /**
     * Leaves a call alone for the first moments it rings, then says whether it
     * still does. Regression: ending a call 136 ms after it began to ring
     * crashed the system process on Android 14, whose ringtone player stops a
     * ringtone it has not finished loading. No person clicks that fast, but a
     * request can arrive that fast, and the phone must not fall over for it.
     */
    private fun stillRingingAfterSettling(): Boolean {
        val ringing = line.current()
        if (ringing.state != LineState.RINGING) return false
        val wait = MIN_RING_MS - (now() - ringing.since)
        if (wait > 0) pause(wait)
        return line.current().state == LineState.RINGING
    }

    /** Runs [attempt] unless this `client_id` was answered in the last 24 hours. */
    private fun once(clientId: String, attempt: () -> String?): CallResultFrame {
        val at = now()
        answered.entries.removeIf { at - it.value.second > REMEMBER_MS }
        answered[clientId]?.let { return it.first }
        val error = attempt()
        val result = CallResultFrame(
            clientId = clientId,
            status = if (error == null) CallResultStatus.OK else CallResultStatus.FAILED,
            error = error,
        )
        answered[clientId] = result to at
        while (answered.size > REMEMBER_MAX) answered.remove(answered.keys.first())
        return result
    }

    private companion object {
        const val REMEMBER_MS = 24 * 60 * 60 * 1000L
        const val REMEMBER_MAX = 500
        const val MIN_RING_MS = 1_500L
        const val SEPARATORS = " ()-. "
        val DIALABLE = Regex("^\\+?[0-9*#]{1,32}$")
    }
}
