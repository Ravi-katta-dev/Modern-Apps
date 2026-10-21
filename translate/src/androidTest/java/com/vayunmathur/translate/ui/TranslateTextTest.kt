package com.vayunmathur.translate.ui

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
 * Text journey: launches the app past the NLLB model download, asserts the source and
 * target language selectors plus the input field are showing, types text, and asserts
 * a translation or loading state follows.
 *
 * Translate wraps Navigation in InitialModelDownloadChecker with NllbModel.FILES, so a
 * fresh device downloads the LARGE NLLB model before any Navigation content shows.
 * The content wait is 5 minutes to cover that first-launch download, which is allowed
 * to finish. The first-run defaults are Auto source and Spanish target, but the last-used
 * pair persists across runs, so the stable marker for the selector row is the swap
 * control between the selectors rather than either language name.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class TranslateTextTest {

    @Test
    fun launch_typeText_showsTranslationOrLoading() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen and the model download: the gate wall never
        // appears and the translate input field is showing. On a fresh device the
        // checker downloads the LARGE NLLB model first, so the wait is 5 minutes.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "translate input never appeared; the model download may still be running",
            device.wait(Until.findObject(By.text(ENTER_TEXT)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "language selectors never appeared",
            device.wait(Until.findObject(By.desc(SWAP_DESCRIPTION)), SHORT_TIMEOUT_MS),
        )

        val fields = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), SHORT_TIMEOUT_MS)
        assertNotNull("translate input field never appeared", fields)
        fields!![0].click()
        fields[0].setText(JOURNEY_INPUT)
        assertNotNull(
            "typed text never appeared in the input field",
            device.wait(Until.findObject(By.text(JOURNEY_INPUT)), SHORT_TIMEOUT_MS),
        )

        // Typed into translated: the output card header paints once the engine has the
        // text, or the loading row shows while it is still working.
        assertNotNull(
            "neither a translation nor a loading state appeared after typing",
            device.wait(Until.findObject(By.text(TRANSLATION_HEADER)), TRANSLATE_TIMEOUT_MS)
                ?: device.wait(Until.findObject(By.text(LOADING_TEXT)), SHORT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after translating.
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
        private const val CONTENT_TIMEOUT_MS = 300_000L
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val SHORT_TIMEOUT_MS = 15_000L
        private const val TRANSLATE_TIMEOUT_MS = 60_000L
        private const val ENTER_TEXT = "Enter text"
        private const val SWAP_DESCRIPTION = "Swap languages"
        private const val TRANSLATION_HEADER = "Translation"
        private const val LOADING_TEXT = "Loading translator…"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        private const val JOURNEY_INPUT = "Hello world"
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
