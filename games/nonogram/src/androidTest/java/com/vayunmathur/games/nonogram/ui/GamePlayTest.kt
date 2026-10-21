package com.vayunmathur.games.nonogram.ui

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
 * Journey test: launches nonogram, flips the mark-mode toggle, and asserts the
 * board stays put rather than navigating away or crashing.
 *
 * Nonogram starts directly at Route.Game, so home and board share the Fill
 * marker; tapping Cross is the play action and Fill is the return interaction.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class GamePlayTest {

    @Test
    fun play_toggleMarkMode_boardStays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears (nonogram
        // has none; gated apps assert the same absence to prove they cleared it).
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        // Home is the board itself: the Fill mode-toggle segment is showing.
        val home = device.wait(Until.findObject(By.text(MARK_MODE_FILL)), CONTENT_TIMEOUT_MS)
        assertNotNull("nonogram board never appeared; the app may have crashed on launch", home)
        // Play action: flip to Cross mode; the board keeps both segments.
        val cross = device.wait(Until.findObject(By.text(MARK_MODE_CROSS)), CONTENT_TIMEOUT_MS)
        assertNotNull("cross toggle never appeared; the board did not finish painting", cross)
        cross!!.click()
        assertNotNull(
            "board left after flipping mark mode; the tap may have navigated away",
            device.wait(Until.findObject(By.text(MARK_MODE_FILL)), CONTENT_TIMEOUT_MS),
        )
        // One interaction: flip back to Fill and confirm the board is still there.
        val fill = device.wait(Until.findObject(By.text(MARK_MODE_FILL)), CONTENT_TIMEOUT_MS)
        assertNotNull("fill toggle vanished after flipping modes", fill)
        fill!!.click()
        assertNotNull(
            "board left after tapping fill; the app may have crashed on interaction",
            device.wait(Until.findObject(By.text(MARK_MODE_FILL)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after the interaction.
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
        private const val MARK_MODE_FILL = "Fill"
        private const val MARK_MODE_CROSS = "Cross"
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
