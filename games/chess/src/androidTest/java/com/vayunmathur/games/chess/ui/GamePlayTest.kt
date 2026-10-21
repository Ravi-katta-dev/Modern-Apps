package com.vayunmathur.games.chess.ui

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
 * Gameplay journey: launch onto the offline board, start a 2-player game, select a pawn, board stays.
 *
 * Chess boots into Route.Game on the Offline Play bottom-bar tab with the board already dealt, so the
 * tab label is the menu marker and the New Game button plus the White move-list header are the board
 * markers. Picks 2-Player Local so the run needs no AI model and White moves first on a fresh board.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on hidden InputManager
 * APIs that no longer exist on current releases, so every compose-rule test dies before its first
 * line. Text polling works for Compose and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class GamePlayTest {

    @Test
    fun launch_newLocalGame_selectPawn_staysOnBoard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the Offline Play tab shows.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "offline tab never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(OFFLINE_TAB_LABEL)), CONTENT_TIMEOUT_MS),
        )

        // Start action: the board's own New Game button, then 2-Player Local in the picker.
        val newGame = device.wait(Until.findObject(By.text(NEW_GAME_BUTTON)), CONTENT_TIMEOUT_MS)
        assertNotNull("new game button never appeared on the offline board", newGame)
        newGame!!.click()
        val twoPlayer = device.wait(Until.findObject(By.text(TWO_PLAYER_LABEL)), CONTENT_TIMEOUT_MS)
        assertNotNull("new-game dialog never offered 2-player local", twoPlayer)
        twoPlayer!!.click()

        // Freshly dealt board: the White move-list header plus the New Game button.
        assertNotNull(
            "chess board never appeared after starting a local game",
            device.wait(Until.findObject(By.text(WHITE_HEADER)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "new game button vanished after starting a local game",
            device.wait(Until.findObject(By.text(NEW_GAME_BUTTON)), CONTENT_TIMEOUT_MS),
        )

        // One interaction: select a white pawn, which only highlights it and shows hint dots.
        val pawn = device.wait(Until.findObject(By.desc(PAWN_DESC)), CONTENT_TIMEOUT_MS)
        assertNotNull("no white pawn appeared to select", pawn)
        pawn!!.click()

        // Board still visible and in front after the selection.
        assertNotNull(
            "chess board vanished after selecting a pawn",
            device.wait(Until.findObject(By.text(NEW_GAME_BUTTON)), CONTENT_TIMEOUT_MS),
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
        private const val OFFLINE_TAB_LABEL = "Offline Play"
        private const val NEW_GAME_BUTTON = "New Game"
        private const val TWO_PLAYER_LABEL = "2-Player Local"
        private const val WHITE_HEADER = "White"
        private const val PAWN_DESC = "White Pawn"
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
