package com.vayunmathur.games.solitaire.ui

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
 * Gameplay journey: launch home, deal FreeCell, tap the tableau, board stays.
 *
 * Solitaire starts at Route.Home with a New Game button; tapping it opens the Select Mode dialog.
 * FreeCell starts immediately with no follow-up config, so it is the deterministic deal. The Undo and
 * Give Up action-bar buttons only exist on the board, so they are the board markers. The tableau tap
 * selects a face-up card, which is highlight-only and opens no dialog.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on hidden InputManager
 * APIs that no longer exist on current releases, so every compose-rule test dies before its first
 * line. Text polling works for Compose and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class GamePlayTest {

    @Test
    fun launch_dealFreeCell_tapTableau_staysOnBoard() {
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
        assertNotNull("solitaire home never appeared; the app may have crashed on launch", newGame)

        // Start action: open the mode picker and deal FreeCell, which needs no further config.
        newGame!!.click()
        clickDialogButton(device, SELECT_MODE_TITLE, FREECELL_MODE, CONTENT_TIMEOUT_MS)

        // Dealt board: the action bar with Undo and Give Up.
        assertNotNull(
            "solitaire board never appeared after dealing freecell",
            device.wait(Until.findObject(By.text(UNDO_BUTTON)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "give up button never appeared on the solitaire board",
            device.wait(Until.findObject(By.text(GIVE_UP_BUTTON)), CONTENT_TIMEOUT_MS),
        )

        // One interaction: tap the middle of the tableau to select a face-up card.
        device.click(device.displayWidth / 2, device.displayHeight * 2 / 3)

        // Board still visible and in front after the tap.
        assertNotNull(
            "solitaire board vanished after tapping the tableau",
            device.wait(Until.findObject(By.text(GIVE_UP_BUTTON)), CONTENT_TIMEOUT_MS),
        )
        assertEquals(packageName, device.currentPackageName)
    }

    /**
     * Clicks [buttonText] inside the dialog titled [dialogTitle].
     *
     * Every mode name also sits on a home stat card, so a plain text search can return the card behind
     * the scrim. Walking up from the unique dialog title stays inside the dialog subtree.
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
        private const val SELECT_MODE_TITLE = "Select Mode"
        private const val FREECELL_MODE = "FreeCell"
        private const val UNDO_BUTTON = "Undo"
        private const val GIVE_UP_BUTTON = "Give Up"
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
