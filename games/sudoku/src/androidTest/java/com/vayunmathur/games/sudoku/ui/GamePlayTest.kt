package com.vayunmathur.games.sudoku.ui

import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gameplay journey: launch home, start a puzzle on the default config, select a cell, hint, board stays.
 *
 * Sudoku starts at Route.Home with a New Game button; tapping it opens the New Puzzle dialog seeded
 * with the 9x9 Medium defaults, and confirming deals the board. The Erase and Give Up action-bar
 * buttons are the board markers, and the top-left cell plus the Hint key are the move.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on hidden InputManager
 * APIs that no longer exist on current releases, so every compose-rule test dies before its first
 * line. Text polling works for Compose and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class GamePlayTest {

    @Test
    fun launch_newPuzzleDefaultConfig_hint_staysOnBoard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the home menu shows.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        val newGame = device.wait(Until.findObject(By.text(NEW_GAME_BUTTON)), CONTENT_TIMEOUT_MS)
        assertNotNull("sudoku home never appeared; the app may have crashed on launch", newGame)

        // Start action: open the picker and confirm the 9x9 Medium defaults inside the dialog.
        newGame!!.click()
        clickDialogButton(device, NEW_PUZZLE_TITLE, NEW_GAME_BUTTON, CONTENT_TIMEOUT_MS)

        // Dealt board: the action bar with Erase and Give Up.
        assertNotNull(
            "sudoku board never appeared after starting a puzzle",
            device.wait(Until.findObject(By.text(ERASE_BUTTON)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "give up button never appeared on the sudoku board",
            device.wait(Until.findObject(By.text(GIVE_UP_BUTTON)), CONTENT_TIMEOUT_MS),
        )

        // One move: select the top-left cell, then take a hint, which fills one cell without dialog.
        val cell = device.wait(Until.findObject(By.descContains(CELL_DESC_PREFIX)), CONTENT_TIMEOUT_MS)
        assertNotNull("no sudoku cell appeared to select", cell)
        cell!!.click()
        val hint = device.wait(Until.findObject(By.text(HINT_BUTTON)), CONTENT_TIMEOUT_MS)
        assertNotNull("hint button never appeared on the sudoku board", hint)
        hint!!.click()

        // Board still visible and in front after the hint.
        assertNotNull(
            "sudoku board vanished after taking a hint",
            device.wait(Until.findObject(By.text(ERASE_BUTTON)), CONTENT_TIMEOUT_MS),
        )
        assertEquals(packageName, device.currentPackageName)
    }

    /**
     * Clicks [buttonText] inside the dialog titled [dialogTitle].
     *
     * The confirm button duplicates the home menu's label, so a plain text search can return the menu
     * button behind the scrim. Walking up from the unique dialog title stays inside the dialog subtree.
     */
    private fun clickDialogButton(device: UiDevice, dialogTitle: String, buttonText: String, timeoutMs: Long) {
        val title = device.wait(Until.findObject(By.text(dialogTitle)), timeoutMs)
        assertNotNull("config dialog $dialogTitle never appeared", title)
        var node = title!!.parent
        repeat(DIALOG_PARENT_DEPTH) {
            val current = node ?: throw AssertionError("button $buttonText not inside $dialogTitle dialog")
            val match = findTextInTree(current, buttonText)
            if (match != null) {
                match.click()
                return
            }
            node = current.parent
        }
        throw AssertionError("button $buttonText not inside $dialogTitle dialog")
    }

    private fun findTextInTree(root: UiObject2, text: String): UiObject2? {
        val queue = ArrayDeque<UiObject2>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.text == text) return node
            queue.addAll(node.children)
        }
        return null
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
        private const val DIALOG_PARENT_DEPTH = 8
        private const val NEW_GAME_BUTTON = "New Game"
        private const val NEW_PUZZLE_TITLE = "New Puzzle"
        private const val ERASE_BUTTON = "Erase"
        private const val GIVE_UP_BUTTON = "Give Up"
        private const val HINT_BUTTON = "Hint"
        private const val CELL_DESC_PREFIX = "Row 1, column 1"
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
