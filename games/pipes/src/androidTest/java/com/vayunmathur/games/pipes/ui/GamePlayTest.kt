package com.vayunmathur.games.pipes.ui

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
 * Journey test: launches pipes, opens the first pack and level, taps Undo,
 * and asserts the board stays put rather than navigating away or crashing.
 *
 * Pipes starts at Route.PackSelector ("Pack Select"); the first pack is the
 * stable "5x5" name from its asset file, the grid title is "Level Select",
 * and the board keeps the stable Undo and Moves labels from the shared boxes.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class GamePlayTest {

    @Test
    fun play_openFirstLevel_tapUndo_boardStays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears (pipes has
        // none; gated apps assert the same absence to prove they cleared it).
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        // Home list is showing: the pack selector title paints first.
        assertNotNull(
            "pipes pack list never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(PACK_SELECT_TITLE)), CONTENT_TIMEOUT_MS),
        )
        // Play action, part one: open the first pack from the home list.
        val pack = device.wait(Until.findObject(By.text(FIRST_PACK_NAME)), CONTENT_TIMEOUT_MS)
        assertNotNull("first pack never appeared; the pack list did not load", pack)
        pack!!.click()
        // Level grid is showing: the level selector title paints next.
        assertNotNull(
            "level grid never appeared; tapping the pack may not have navigated",
            device.wait(Until.findObject(By.text(LEVEL_SELECT_TITLE)), CONTENT_TIMEOUT_MS),
        )
        // Play action, part two: open the first level from the grid.
        val level = device.wait(Until.findObject(By.text(FIRST_LEVEL_CELL)), CONTENT_TIMEOUT_MS)
        assertNotNull("first level cell never appeared; the grid did not load", level)
        level!!.click()
        // Board is showing: the Undo action button paints with the board.
        val board = device.wait(Until.findObject(By.text(BOARD_UNDO)), CONTENT_TIMEOUT_MS)
        assertNotNull("pipes board never appeared; opening the level may have crashed", board)
        // One interaction: tap Undo (a no-op on a fresh board) and confirm Moves stays.
        board!!.click()
        assertNotNull(
            "board left after tapping undo; the app may have crashed on interaction",
            device.wait(Until.findObject(By.text(BOARD_MOVES)), CONTENT_TIMEOUT_MS),
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
        private const val PACK_SELECT_TITLE = "Pack Select"
        private const val FIRST_PACK_NAME = "5×5"
        private const val LEVEL_SELECT_TITLE = "Level Select"
        private const val FIRST_LEVEL_CELL = "1"
        private const val BOARD_UNDO = "Undo"
        private const val BOARD_MOVES = "Moves"
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
