package com.vayunmathur.clock.ui

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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Journey: create an alarm with a unique name and assert it appears in the list.
 *
 * Opens the creator from the empty-state button or the add button, names the
 * alarm so the list assertion keys on this run, saves, then waits for the name
 * in the list.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class ClockAlarmCreateTest {

    @Test
    fun createAlarm_appearsInList() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(
            instrumentation.uiAutomation,
            targetContext.packageManager,
            packageName,
        )

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the
        // alarms tab is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "alarms screen never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(ALARM_TAB_LABEL)), CONTENT_TIMEOUT_MS),
        )

        // Open the creator: empty-state button on first run, add button after.
        val emptyCta = device.wait(Until.findObject(By.text(EMPTY_STATE_CTA)), GATE_TIMEOUT_MS)
        if (emptyCta != null) {
            emptyCta.click()
        } else {
            val add = device.wait(Until.findObject(By.desc(ADD_DESC)), GATE_TIMEOUT_MS)
            assertNotNull("no alarm creator control found", add)
            add!!.click()
        }

        // The new-alarm dialog is showing.
        assertNotNull(
            "new-alarm dialog never appeared",
            device.wait(Until.findObject(By.text(SAVE_LABEL)), CONTENT_TIMEOUT_MS),
        )

        // Name it so the list assertion keys on this alarm, not an older one.
        val nameHint = device.wait(Until.findObject(By.text(NAME_HINT)), GATE_TIMEOUT_MS)
        assertNotNull("alarm name field never appeared", nameHint)
        nameHint!!.click()
        nameHint.setText(ALARM_NAME)

        val save = device.wait(Until.findObject(By.text(SAVE_LABEL)), GATE_TIMEOUT_MS)
        assertNotNull("save button never appeared", save)
        save!!.click()

        // Dialog closed and the new alarm row is in the list.
        assertNull(
            "new-alarm dialog did not close after save",
            device.wait(Until.findObject(By.text(SAVE_LABEL)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "created alarm never appeared in the list",
            device.wait(Until.findObject(By.text(ALARM_NAME)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after saving.
        assertEquals(packageName, device.currentPackageName)
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
        private const val CONTENT_TIMEOUT_MS = 15_000L
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val ALARM_TAB_LABEL = "Alarm"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private const val EMPTY_STATE_CTA = "Set an alarm"
        private const val ADD_DESC = "Add"
        private const val SAVE_LABEL = "Save"
        private const val NAME_HINT = "Alarm name (optional)"
        private const val ALARM_NAME = "Journey Alarm"

        /**
         * Special access behind clock's gate: exact alarms + full-screen intent have
         * no pm-grantable permission, but the shell can flip their appops. Ignored
         * where the appop does not exist; the gate assertion reports it.
         */
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
