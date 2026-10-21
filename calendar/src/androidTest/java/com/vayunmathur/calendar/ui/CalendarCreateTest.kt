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
 * Journey: FAB into the editor, type title, save, agenda shows the event.
 * UiDevice polling, not compose-test: Espresso idle-sync is broken on API 37.
 */
@RunWith(AndroidJUnit4::class)
class CalendarCreateTest {

    @Test
    fun createEvent_save_showsInAgenda() {
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
        ensureCalendar(device)
        device.findObject(By.desc(ADD_DESCRIPTION))!!.click()
        assertNotNull(
            "event editor never opened",
            device.wait(Until.findObject(By.text(TITLE_LABEL)), CONTENT_TIMEOUT_MS),
        )
        val fields = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), CONTENT_TIMEOUT_MS)
        assertNotNull("title field never appeared", fields)
        fields!![0].click()
        fields[0].setText(EVENT_TITLE)
        device.findObject(By.desc(SAVE_DESCRIPTION))!!.click()
        assertNotNull(
            "created event never appeared in agenda",
            device.wait(Until.findObject(By.text(EVENT_TITLE)), CONTENT_TIMEOUT_MS),
        )
        assertEquals(packageName, device.currentPackageName)
    }

    private fun ensureCalendar(device: UiDevice) {
        val create = device.wait(Until.findObject(By.text(CREATE_CALENDAR)), SHORT_TIMEOUT_MS) ?: return
        create.click()
        assertNotNull(
            "new calendar dialog never opened",
            device.wait(Until.findObject(By.text(NEW_CALENDAR_TITLE)), CONTENT_TIMEOUT_MS),
        )
        val fields = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), CONTENT_TIMEOUT_MS)
        assertNotNull("calendar name field never appeared", fields)
        fields!![0].click()
        fields[0].setText(CALENDAR_NAME)
        device.findObject(By.text(CONFIRM_CREATE))!!.click()
        assertNotNull(
            "did not return to agenda after creating calendar",
            device.wait(Until.findObject(By.text(AGENDA_TAB)), CONTENT_TIMEOUT_MS),
        )
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
        private const val TITLE_LABEL = "Title"
        private const val ADD_DESCRIPTION = "Add"
        private const val SAVE_DESCRIPTION = "Save"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        private const val EVENT_TITLE = "Journey Standup Alpha"
        private const val CREATE_CALENDAR = "Create a calendar"
        private const val NEW_CALENDAR_TITLE = "New local calendar"
        private const val CALENDAR_NAME = "JourneyCal"
        private const val CONFIRM_CREATE = "Create"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
