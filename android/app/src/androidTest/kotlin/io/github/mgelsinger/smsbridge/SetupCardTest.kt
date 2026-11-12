package io.github.mgelsinger.smsbridge

import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: after accepting the system's "always run in background" dialog, the
 * Setup card kept offering "Allow background use", because the exemption was read
 * when the dialog opened instead of when the user came back.
 */
@RunWith(AndroidJUnit4::class)
class SetupCardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var wasExempt = false

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun exempt() = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Every text in the active window, from one walk of the tree (Compose does not answer
     * findAccessibilityNodeInfosByText). Empty while another window is in front.
     */
    private fun texts(): Set<String> {
        val found = mutableSetOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.let { found += it.toString() }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return found
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        assertTrue(what, condition())
    }

    @Before
    fun setUp() {
        wasExempt = exempt()
        shell("dumpsys deviceidle whitelist -${context.packageName}")
        waitFor("the exemption is removed for the test") { !exempt() }
    }

    @After
    fun tearDown() {
        shell("dumpsys deviceidle whitelist ${if (wasExempt) "+" else "-"}${context.packageName}")
    }

    @Test
    fun backgroundUseButtonGoesAwayOnceTheExemptionIsGranted() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("the button is offered while not exempt") { BUTTON in texts() }

            // What the system dialog does: it covers the app, the user accepts, the app resumes.
            scenario.moveToState(Lifecycle.State.STARTED)
            shell("dumpsys deviceidle whitelist +${context.packageName}")
            waitFor("the exemption is granted") { exempt() }
            scenario.moveToState(Lifecycle.State.RESUMED)

            // The screen itself must be back in front, or "no button" would prove nothing.
            waitFor("the button is gone once the app is exempt") {
                val now = texts()
                ALWAYS_SHOWN in now && BUTTON !in now
            }
        }
    }

    private companion object {
        const val BUTTON = "Allow background use"
        const val ALWAYS_SHOWN = "Paired PCs"
    }
}
