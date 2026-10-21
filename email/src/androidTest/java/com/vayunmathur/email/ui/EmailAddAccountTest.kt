package com.vayunmathur.email.ui

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First-run gate journey: no accounts shows AddAccountScreen (see MainContent).
 *
 * The Gmail preset stays in-app (Outlook needs an external browser that never
 * finishes headless), so this fills its form and asserts validation holds the
 * gate: no inbox appears and the app stays foreground. UiDevice polling only;
 * compose-test idle-sync is broken on current releases.
 */
@RunWith(AndroidJUnit4::class)
class EmailAddAccountTest {

    @Test
    fun launch_presetForm_rendersAndValidates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName
        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)
        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "add-account gate never appeared",
            device.wait(Until.findObject(By.text(ADD_ACCOUNT_TITLE)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "provider picker never appeared",
            device.wait(Until.findObject(By.text(PROVIDER_PICKER_TITLE)), CONTENT_TIMEOUT_MS),
        )
        requireNotNull(device.wait(Until.findObject(By.text(GMAIL_PRESET)), CONTENT_TIMEOUT_MS)) {
            "Gmail preset row missing"
        }.click()
        assertNotNull(
            "account form never rendered",
            device.wait(Until.findObject(By.text(EMAIL_FIELD_LABEL)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "save action never rendered",
            device.wait(Until.findObject(By.text(SAVE_BUTTON)), CONTENT_TIMEOUT_MS),
        )
        requireNotNull(device.wait(Until.findObject(By.text(EMAIL_FIELD_LABEL)), CONTENT_TIMEOUT_MS)) {
            "email field missing"
        }.click()
        val edit = device.wait(Until.findObject(By.clazz("android.widget.EditText")), CONTENT_TIMEOUT_MS)
        assertNotNull("email field never took focus", edit)
        edit!!.setText("not-an-email")
        assertNotNull(
            "save action disappeared after invalid input",
            device.wait(Until.findObject(By.text(SAVE_BUTTON)), CONTENT_TIMEOUT_MS),
        )
        val save = requireNotNull(device.wait(Until.findObject(By.text(SAVE_BUTTON)), CONTENT_TIMEOUT_MS)) {
            "save button missing"
        }
        assertFalse(
            "save must stay disabled until a valid email and password are entered",
            save.isClickable,
        )
        assertNull(
            "inbox shown without a valid account; validation did not hold the gate",
            device.wait(Until.findObject(By.text(INBOX_TITLE)), GATE_TIMEOUT_MS),
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
        private const val ADD_ACCOUNT_TITLE = "Add Account"
        private const val PROVIDER_PICKER_TITLE = "Choose your email provider"
        private const val GMAIL_PRESET = "Gmail"
        private const val EMAIL_FIELD_LABEL = "Email address"
        private const val SAVE_BUTTON = "Test connection & save"
        private const val INBOX_TITLE = "Unified Inbox"
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
