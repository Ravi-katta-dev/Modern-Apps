package com.vayunmathur.pdf.ui

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
 * Journey: launch onto the picker, open Cut and Glue, its title and hint show.
 *
 * Pdf lands on its initial picker when launched with no document intent. Tapping the
 * cut-and-glue button swaps to the CutGlueScreen in-app (no file picker involved), whose
 * title and empty-state hint only exist on that screen, so they prove the open landed.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class PdfHomeTest {

    @Test
    fun cutAndGlue_opensFromPicker_showsTitleAndHint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the picker button shows.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "picker never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(OPEN_PDF_LABEL)), CONTENT_TIMEOUT_MS),
        )

        // Core interaction: open the Cut and Glue screen from the picker.
        val cutGlueButton = device.wait(Until.findObject(By.text(CUT_GLUE_BUTTON)), SHORT_TIMEOUT_MS)
        assertNotNull("cut and glue button never appeared", cutGlueButton)
        cutGlueButton!!.click()
        // Cut and Glue content is actually visible: its title plus the empty-state hint.
        assertNotNull(
            "cut and glue title never appeared after tapping its button",
            device.wait(Until.findObject(By.text(CUT_GLUE_TITLE)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "cut and glue hint never appeared after tapping its button",
            device.wait(Until.findObject(By.text(CUT_GLUE_HINT)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after opening the screen.
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
        private const val SHORT_TIMEOUT_MS = 5_000L
        private const val OPEN_PDF_LABEL = "Open PDF"
        private const val CUT_GLUE_BUTTON = "PDF cut and glue"
        private const val CUT_GLUE_TITLE = "Cut & Glue"
        private const val CUT_GLUE_HINT = "Tap + to append a PDF or image"
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
