package com.vayunmathur.openassistant.ui

import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home journey: launches the app past the model download, asserts the chat input is
 * showing, types a message, sends it, and asserts the sent bubble lands in the thread.
 *
 * MainActivity wraps Navigation in InitialModelDownloadChecker, so a fresh device
 * downloads large models before chat paints; the content wait is 5 minutes to let that
 * first-launch download finish rather than failing early. Inference then streams back
 * with no stable loading literal, so the deterministic arrival marker for the send is
 * the user bubble echoing the text plus the composer clearing.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class AssistantHomeTest {

    @Test
    fun launch_sendMessage_showsSentBubble() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen and the model download: the gate wall never
        // appears and the empty chat title is showing. On a fresh device the checker
        // downloads the models first, so the wait is 5 minutes.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "assistant chat never appeared; the model download may still be running",
            device.wait(Until.findObject(By.text(NEW_CONVERSATION_TITLE)), CONTENT_TIMEOUT_MS),
        )

        val field = device.wait(Until.findObject(By.clazz(EDIT_TEXT_CLASS)), SHORT_TIMEOUT_MS)
        assertNotNull("chat input never appeared", field)
        field!!.click()
        field.setText(JOURNEY_MESSAGE)

        val send = device.wait(Until.findObject(By.desc(SEND_DESCRIPTION)), SHORT_TIMEOUT_MS)
        assertNotNull("send button never appeared", send)
        send!!.click()

        // Sent into the thread: the user bubble echoes the text and the composer clears.
        assertNotNull(
            "sent message never appeared in the thread",
            device.wait(Until.findObject(By.text(JOURNEY_MESSAGE)), RESPONSE_TIMEOUT_MS),
        )
        assertInputCleared(device)
        // Still alive in front: the process did not crash after sending.
        assertEquals(packageName, device.currentPackageName)
    }

    private fun assertInputCleared(device: UiDevice) {
        val deadline = System.currentTimeMillis() + CLEAR_TIMEOUT_MS
        var cleared = false
        while (System.currentTimeMillis() < deadline && !cleared) {
            val field = device.findObject(By.clazz(EDIT_TEXT_CLASS))
            cleared = field == null || field.text != JOURNEY_MESSAGE
        }
        assertTrue("chat input still holds the message; the send may not have dispatched", cleared)
    }

    private fun grantAllRequestedPermissions(
        uiAutomation: UiAutomation,
        packageManager: PackageManager,
        packageName: String,
    ) {
        val info = try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (e: PackageManager.NameNotFoundException) {
            throw AssertionError("package $packageName not installed", e)
        }
        for (permission in info.requestedPermissions.orEmpty()) {
            try {
                uiAutomation.executeShellCommand("pm grant $packageName $permission").close()
            } catch (ignored: SecurityException) {
                // Not a headlessly grantable permission: nothing to do.
            } catch (ignored: IOException) {
                // Shell pipe failed: the permission stays ungranted and the
                // content assertions below report it.
            }
        }
        for (command in SPECIAL_ACCESS_COMMANDS) {
            try {
                uiAutomation.executeShellCommand(command.format(packageName)).close()
            } catch (ignored: SecurityException) {
                // Appop missing on this release: the gate assertion reports it.
            } catch (ignored: IOException) {
                // Shell pipe failed: the gate assertion below reports it.
            }
        }
    }

    private companion object {
        private const val CONTENT_TIMEOUT_MS = 300_000L
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val SHORT_TIMEOUT_MS = 15_000L
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val CLEAR_TIMEOUT_MS = 10_000L
        private const val NEW_CONVERSATION_TITLE = "New Conversation"
        private const val SEND_DESCRIPTION = "Send"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        private const val JOURNEY_MESSAGE = "Hello assistant"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"

        /**
         * Special access behind some apps' gates: exact alarms + full-screen intent
         * have no pm-grantable permission, but the shell can flip their appops.
         * Ignored where the appop does not exist; the gate assertion reports it.
         */
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
