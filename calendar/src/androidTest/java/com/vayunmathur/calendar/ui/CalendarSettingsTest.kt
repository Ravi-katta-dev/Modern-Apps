package com.vayunmathur.calendar.ui

import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Journey: settings icon into Settings, preference rows visible, back to agenda.
 * UiDevice polling, not compose-test: Espresso idle-sync is broken on API 37.
 */
@RunWith(AndroidJUnit4::class)
class CalendarSettingsTest {

    @Test
    fun openSettings_preferencesVisible_backToAgenda() {
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
            "agenda view never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(AGENDA_TAB)), CONTENT_TIMEOUT_MS),
        )
        val settings = device.wait(Until.findObject(By.desc(SETTINGS_DESCRIPTION)), CONTENT_TIMEOUT_MS)
        assertNotNull("settings entry never appeared", settings)
        settings!!.click()
        assertNotNull(
            "settings screen never opened",
            device.wait(Until.findObject(By.text(SETTINGS_TITLE)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "theme preference never appeared",
            device.wait(Until.findObject(By.text(THEME_ROW)), SHORT_TIMEOUT_MS),
        )
        assertNotNull(
            "default layout preference never appeared",
            device.wait(Until.findObject(By.text(LAYOUT_ROW)), SHORT_TIMEOUT_MS),
        )
        device.pressBack()
        assertNotNull(
            "did not return to agenda",
            device.wait(Until.findObject(By.text(AGENDA_TAB)), CONTENT_TIMEOUT_MS),
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
        val commands = info.requestedPermissions.orEmpty().map { "pm grant $packageName $it" } +
            SPECIAL_ACCESS_COMMANDS.map { it.format(packageName) }
        for (command in commands) {
            try {
                uiAutomation.executeShellCommand(command).close()
            } catch (ignored: Exception) {
                // Not grantable here; the gate assertion below reports it.
            }
        }
    }

    private companion object {
        private const val CONTENT_TIMEOUT_MS = 15_000L
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val SHORT_TIMEOUT_MS = 5_000L
        private const val AGENDA_TAB = "Agenda"
        private const val SETTINGS_DESCRIPTION = "Settings"
        private const val SETTINGS_TITLE = "Settings"
        private const val THEME_ROW = "Theme"
        private const val LAYOUT_ROW = "Default Layout"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
