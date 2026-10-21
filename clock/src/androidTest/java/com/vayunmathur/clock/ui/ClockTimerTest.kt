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
 * Journey: open the timer tab, enter ten seconds on the keypad, start it, and
 * assert a running timer card is visible.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class ClockTimerTest {

    @Test
    fun timerStart_showsRunningTimer() {
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

        // Go to the timer tab.
        val timerTab = device.wait(Until.findObject(By.text(TIMER_TAB_LABEL)), CONTENT_TIMEOUT_MS)
        assertNotNull("timer tab never appeared", timerTab)
        timerTab!!.click()

        // Keypad shows while empty; the add button reveals it when timers exist.
        var nameHint = device.wait(Until.findObject(By.text(KEYPAD_NAME_HINT)), GATE_TIMEOUT_MS)
        if (nameHint == null) {
            val add = device.wait(Until.findObject(By.desc(ADD_DESC)), GATE_TIMEOUT_MS)
            assertNotNull("timer keypad never appeared", add)
            add!!.click()
            nameHint = device.wait(Until.findObject(By.text(KEYPAD_NAME_HINT)), CONTENT_TIMEOUT_MS)
        }
        assertNotNull("timer keypad never appeared", nameHint)

        // Enter ten seconds on the keypad, then start.
        val one = device.wait(Until.findObject(By.text(DIGIT_ONE)), GATE_TIMEOUT_MS)
        assertNotNull("timer keypad digit never appeared", one)
        one!!.click()
        val zero = device.wait(Until.findObject(By.text(DIGIT_ZERO)), GATE_TIMEOUT_MS)
        assertNotNull("timer keypad digit never appeared", zero)
        zero!!.click()

        val play = device.wait(Until.findObject(By.desc(PLAY_DESC)), GATE_TIMEOUT_MS)
        assertNotNull("timer start control never appeared", play)
        play!!.click()

        assertNotNull(
            "started timer never appeared",
            device.wait(Until.findObject(By.text(RUNNING_LABEL)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after starting.
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
        private const val TIMER_TAB_LABEL = "Timer"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private const val KEYPAD_NAME_HINT = "Timer name (optional)"
        private const val ADD_DESC = "Add"
        private const val PLAY_DESC = "Play"
        private const val DIGIT_ONE = "1"
        private const val DIGIT_ZERO = "0"
        private const val RUNNING_LABEL = "Running"

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
