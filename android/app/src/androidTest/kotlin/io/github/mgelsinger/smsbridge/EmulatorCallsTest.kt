package io.github.mgelsinger.smsbridge

import android.Manifest
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.mgelsinger.smsbridge.core.CallDesk
import io.github.mgelsinger.smsbridge.core.EventHub
import io.github.mgelsinger.smsbridge.core.LineTracker
import io.github.mgelsinger.smsbridge.platform.AndroidTelephone
import io.github.mgelsinger.smsbridge.platform.CallWatcher
import io.github.mgelsinger.smsbridge.protocol.CallAction
import io.github.mgelsinger.smsbridge.protocol.CallActionFrame
import io.github.mgelsinger.smsbridge.protocol.CallFrame
import io.github.mgelsinger.smsbridge.protocol.CallKind
import io.github.mgelsinger.smsbridge.protocol.CallResultStatus
import io.github.mgelsinger.smsbridge.protocol.CallStateFrame
import io.github.mgelsinger.smsbridge.protocol.DialFrame
import io.github.mgelsinger.smsbridge.protocol.Direction
import io.github.mgelsinger.smsbridge.protocol.LineState
import io.github.mgelsinger.smsbridge.protocol.WsFrame
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Calls against the emulator's real telephony: the phone-state broadcast, the
 * call log, and TelecomManager, none of which a JVM test can reach.
 *
 * An emulator call starts on the host. The test asks for one by writing a cue
 * to logcat, `ring <number>` or `hangup <number>`, which
 * tools/scripts/ci-emulator-test.sh turns into `adb emu gsm call` and
 * `adb emu gsm cancel`.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorCallsTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = { text: String -> Log.i("SmsBridgeTest", text); Unit }
    private val hub = EventHub()
    private val phone = AndroidTelephone(context)
    private val line = LineTracker()
    private val desk = CallDesk(phone, line, log = log)
    private val watcher = CallWatcher(context, phone, line, hub, log, scope)
    // Kept apart: a call log entry and the line going idle can arrive in either order.
    private val lines = LinkedBlockingQueue<CallStateFrame>()
    private val logged = LinkedBlockingQueue<CallFrame>()

    private fun cue(text: String) {
        Log.i("SmsBridgeCue", text)
    }

    /**
     * What the system says the line is doing, or null while it will not say.
     * Regression: a permission granted for a test takes a moment to reach the
     * app-op behind it, and until then this call throws. The first tests of a
     * run failed in setUp on that, before any call was made.
     */
    @Suppress("DEPRECATION")
    private fun systemCallState(): Int? =
        runCatching { context.getSystemService(TelephonyManager::class.java).callState }.getOrNull()

    private fun systemSaysIdle() = systemCallState() == TelephonyManager.CALL_STATE_IDLE

    private fun awaitPermissionsInEffect() {
        val deadline = System.currentTimeMillis() + 30_000
        while (systemCallState() == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("the phone-state permission took effect within 30 s", systemCallState() != null)
    }

    private fun hangUpEverything() {
        cue("hangup $CALLER")
        @Suppress("DEPRECATION")
        runCatching { context.getSystemService(TelecomManager::class.java).endCall() }
        val deadline = System.currentTimeMillis() + 20_000
        while (!systemSaysIdle() && System.currentTimeMillis() < deadline) Thread.sleep(250)
    }

    @Before
    fun setUp() {
        awaitPermissionsInEffect()
        hangUpEverything()
        assertTrue("the emulator's line is idle before the test", systemSaysIdle())
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.frames.collect { frame: WsFrame ->
                when (frame) {
                    is CallStateFrame -> lines.add(frame)
                    is CallFrame -> logged.add(frame)
                    else -> Unit
                }
            }
        }
        watcher.start()
        runBlocking { withTimeout(30_000) { watcher.awaitWatching() } }
        assertEquals(LineState.IDLE, desk.lineFrame().state)
        val can = desk.abilities()
        assertTrue("every call permission was granted: $can", can.history && can.dial && can.answer && can.end)
    }

    @After
    fun tearDown() {
        watcher.stop()
        scope.cancel()
        hangUpEverything()
    }

    private fun awaitLine(state: LineState, seconds: Long = 90): CallStateFrame {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            val f = lines.poll(1, TimeUnit.SECONDS) ?: continue
            Log.i("SmsBridgeTest", "line ${f.state} ${f.direction} number=${f.number != null}")
            if (f.state == state) return f
        }
        throw AssertionError("the line did not become $state within $seconds s; it is ${desk.lineFrame().state}")
    }

    private fun awaitLogged(seconds: Long = 60): CallFrame =
        logged.poll(seconds, TimeUnit.SECONDS) ?: throw AssertionError("no call log entry was pushed within $seconds s")

    private fun act(action: CallAction) = desk.act(CallActionFrame(clientId = UUID.randomUUID().toString(), action = action))

    @Test
    fun anInboundCallRingsWithItsNumberIsAnsweredAndLandsInTheHistory() {
        cue("ring $CALLER")
        val ringing = awaitLine(LineState.RINGING)
        assertEquals(Direction.IN, ringing.direction)
        // The number may arrive in a second report a moment after the first.
        val deadline = System.currentTimeMillis() + 10_000
        while (desk.lineFrame().number == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("the caller's number is known while it rings", desk.lineFrame().number.orEmpty().endsWith(CALLER))

        val answered = act(CallAction.ANSWER)
        assertEquals("answer: ${answered.error}", CallResultStatus.OK, answered.status)
        val active = awaitLine(LineState.ACTIVE)
        assertEquals(Direction.IN, active.direction)
        assertTrue("the caller is still named once answered", active.number.orEmpty().endsWith(CALLER))

        cue("hangup $CALLER")
        awaitLine(LineState.IDLE)
        val entry = awaitLogged().call
        assertEquals(CallKind.IN, entry.kind)
        assertTrue(entry.number.orEmpty().endsWith(CALLER))
        val history = checkNotNull(desk.page(null, 500)) { "the call log can be read" }
        assertTrue("the entry is in the history", history.calls.any { it.id == entry.id })
    }

    @Test
    fun aRingingCallIsDeclinedFromTheClient() {
        cue("ring $CALLER")
        awaitLine(LineState.RINGING)
        // Declined at once, as fast as a request can arrive: the phone app itself must
        // hold off long enough not to trip Android 14's ringtone player.
        val declined = act(CallAction.END)
        assertEquals("decline: ${declined.error}", CallResultStatus.OK, declined.status)
        awaitLine(LineState.IDLE)
        val entry = awaitLogged().call
        Log.i("SmsBridgeTest", "a declined call is logged as ${entry.kind}")
        // Which of the two Android writes for a declined call has changed between versions.
        assertTrue("declined, not answered: ${entry.kind}", entry.kind == CallKind.REJECTED || entry.kind == CallKind.MISSED)
        assertEquals(0L, entry.durationSeconds)
    }

    @Test
    fun aCallDialedFromTheClientGoesOutOnceAndIsEndedFromTheClient() {
        val request = DialFrame(clientId = UUID.randomUUID().toString(), to = CALLEE)
        val dialed = desk.dial(request)
        assertEquals("dial: ${dialed.error}", CallResultStatus.OK, dialed.status)
        val active = awaitLine(LineState.ACTIVE)
        assertEquals(Direction.OUT, active.direction)
        assertEquals(CALLEE, active.number)

        // The same request again must not start a second call.
        assertEquals(CallResultStatus.OK, desk.dial(request).status)
        val busy = desk.dial(DialFrame(clientId = UUID.randomUUID().toString(), to = CALLEE))
        assertEquals("a call is already in progress", busy.error)

        val ended = act(CallAction.END)
        assertEquals("end: ${ended.error}", CallResultStatus.OK, ended.status)
        awaitLine(LineState.IDLE)
        val entry = awaitLogged().call
        assertEquals(CallKind.OUT, entry.kind)
        assertTrue(entry.number.orEmpty().endsWith(CALLEE))
        Thread.sleep(3_000)
        assertTrue("one request, one call, one entry", logged.isEmpty())
    }

    private companion object {
        const val CALLER = "5551234"
        const val CALLEE = "5550100"
    }
}
