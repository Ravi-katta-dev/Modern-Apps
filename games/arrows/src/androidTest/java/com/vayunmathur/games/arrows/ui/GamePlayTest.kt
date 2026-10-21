package com.vayunmathur.games.arrows.ui

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
 * Gameplay journey: launch onto the board, restart the level, tap an arrow, board stays.
 *
 * Arrows boots straight into Route.Game with the mode selector on top, so the Levels label is both
 * the menu marker and the board marker. Defaults to the stored mode, normally Levels. Restart on a
 * fresh board restarts into itself with no confirm dialog.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on hidden InputManager
 * APIs that no longer exist on current releases, so every compose-rule test dies before its first
 * line. Text polling works for Compose and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class GamePlayTest {

    @Test
    fun launch_restart_tapArrow_staysOnBoard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the Levels mode label shows.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "arrows board never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(MODE_LEVELS_LABEL)), CONTENT_TIMEOUT_MS),
        )

        // Start action: restart the level from the board's own button.
        val restart = device.wait(Until.findObject(By.text(RESTART_BUTTON)), CONTENT_TIMEOUT_MS)
        assertNotNull("restart button never appeared on the arrows board", restart)
        restart!!.click()

        // Board visible after restart: the Levels label is still showing.
        assertNotNull(
            "arrows board vanished after restart",
            device.wait(Until.findObject(By.text(MODE_LEVELS_LABEL)), CONTENT_TIMEOUT_MS),
        )

        // One move: tap the first arrow cell.
        val arrow = device.wait(Until.findObject(By.descContains(ARROW_DESC)), CONTENT_TIMEOUT_MS)
        assertNotNull("no arrow cell appeared to tap", arrow)
        arrow!!.click()

        // Board still visible and in front after the tap.
        assertNotNull(
            "arrows board vanished after tapping an arrow",
            device.wait(Until.findObject(By.text(MODE_LEVELS_LABEL)), CONTENT_TIMEOUT_MS),
        )
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
        private const val MODE_LEVELS_LABEL = "Levels"
        private const val RESTART_BUTTON = "Restart"
        private const val ARROW_DESC = "Arrow pointing"
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
