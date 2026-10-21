package com.vayunmathur.games.logicgate.ui

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
 * Journey test: launches logicgate, opens the first level from the map, taps
 * the editor undo button, and asserts the editor stays put rather than
 * navigating away or crashing.
 *
 * LogicGate boots into the progression map (Route.Progression); the first
 * level is always unlocked so its node carries the stable PLAY label, and the
 * editor top bar carries the level name plus the stable undo glyph. The undo
 * tap is a no-op on a fresh circuit, which is exactly what makes it safe.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class GamePlayTest {

    @Test
    fun play_openFirstLevel_tapUndo_editorStays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the
        // progression subtitle is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "level map never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(PROGRESSION_SUBTITLE)), CONTENT_TIMEOUT_MS),
        )
        // Play action: open the first level, whose node always offers PLAY.
        val play = device.wait(Until.findObject(By.text(LEVEL_PLAY_LABEL)), CONTENT_TIMEOUT_MS)
        assertNotNull("no playable level node; the level map did not load its nodes", play)
        play!!.click()
        // Editor is showing: its top bar carries the undo glyph, which the map
        // never draws, so it proves the navigation landed in the editor.
        val board = device.wait(Until.findObject(By.text(EDITOR_UNDO_GLYPH)), CONTENT_TIMEOUT_MS)
        assertNotNull("circuit editor never appeared; tapping play may not have navigated", board)
        assertNotNull(
            "first level title missing; the editor may have opened the wrong level",
            device.wait(Until.findObject(By.text(FIRST_LEVEL_NAME)), CONTENT_TIMEOUT_MS),
        )
        // One interaction: tap undo (a no-op on a fresh circuit) and confirm
        // the editor is still there.
        board!!.click()
        assertNotNull(
            "editor left after tapping undo; the app may have crashed on interaction",
            device.wait(Until.findObject(By.text(FIRST_LEVEL_NAME)), CONTENT_TIMEOUT_MS),
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
        private const val PROGRESSION_SUBTITLE = "Start with NAND. End with a computer."
        private const val LEVEL_PLAY_LABEL = "PLAY"
        private const val FIRST_LEVEL_NAME = "NOT"
        private const val EDITOR_UNDO_GLYPH = "↩"
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
