package com.vayunmathur.email.ui

import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EML viewer journey: VIEW a test .eml via intent (the EmlViewer bypass in
 * MainContent needs no account), asserting the parsed content renders and the
 * forward action is offered.
 *
 * The .eml is written to the app cache and served through the app's own
 * FileProvider, so no external file or account exists. UiDevice polling only;
 * compose-test idle-sync is broken on current releases.
 */
@RunWith(AndroidJUnit4::class)
class EmailComposerTest {

    @Test
    fun viewEml_showsContentWithForwardAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName
        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)
        val eml = File(targetContext.cacheDir, "journey.eml")
        eml.writeText(TEST_EML)
        val uri = FileProvider.getUriForFile(targetContext, "$packageName.fileprovider", eml)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "message/rfc822")
            .setClassName(packageName, "$packageName.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        targetContext.startActivity(intent)
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "eml subject never rendered; the viewer may have failed to parse",
            device.wait(Until.findObject(By.text(EML_SUBJECT)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "eml body never rendered",
            device.wait(Until.findObject(By.textContains(EML_BODY_SNIPPET)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "forward action missing from the viewer",
            device.wait(Until.findObject(By.desc(FORWARD_DESC)), CONTENT_TIMEOUT_MS),
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
            } catch (ignored: SecurityException) { // Not grantable headlessly.
            } catch (ignored: IOException) { // Pipe failed; content asserts report it.
            }
        }
        for (command in SPECIAL_ACCESS_COMMANDS) {
            try {
                uiAutomation.executeShellCommand(command.format(packageName)).close()
            } catch (ignored: SecurityException) { // Appop missing on this release.
            } catch (ignored: IOException) { // Pipe failed; gate assert reports it.
            }
        }
    }

    private companion object {
        private const val CONTENT_TIMEOUT_MS = 15_000L
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private const val EML_SUBJECT = "Journey hello"
        private const val EML_BODY_SNIPPET = "Hello from the journey test"
        private const val FORWARD_DESC = "Forward"
        private const val TEST_EML = "From: journey@example.com\n" +
            "To: me@example.com\n" +
            "Subject: Journey hello\n" +
            "Date: Wed, 21 Oct 2026 12:00:00 +0000\n" +
            "Content-Type: text/plain; charset=utf-8\n" +
            "\n" +
            "Hello from the journey test.\n"
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
