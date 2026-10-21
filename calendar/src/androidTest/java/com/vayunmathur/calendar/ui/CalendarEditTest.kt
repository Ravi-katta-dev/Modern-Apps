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
 * Journey: open a created event, edit its title, save, agenda shows the new title.
 * UiDevice polling, not compose-test: Espresso idle-sync is broken on API 37.
 */
@RunWith(AndroidJUnit4::class)
class CalendarEditTest {

    @Test
    fun openEvent_editTitle_savedChipShows() {
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
        setTitle(device, ORIGINAL_TITLE)
        val chip = device.wait(Until.findObject(By.text(ORIGINAL_TITLE)), CONTENT_TIMEOUT_MS)
        assertNotNull("created event never appeared in agenda", chip)
        chip!!.click()
        val edit = device.wait(Until.findObject(By.desc(EDIT_DESCRIPTION)), CONTENT_TIMEOUT_MS)
        assertNotNull("event edit entry never appeared", edit)
        edit!!.click()
        setTitle(device, UPDATED_TITLE)
        assertNotNull(
            "edited title never appeared",
            device.wait(Until.findObject(By.text(UPDATED_TITLE)), CONTENT_TIMEOUT_MS),
        )
        assertEquals(packageName, device.currentPackageName)
    }

    private fun setTitle(device: UiDevice, title: String) {
        assertNotNull("event editor never opened", device.wait(Until.findObject(By.text(TITLE_LABEL)), CONTENT_MS))
        val fields = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), CONTENT_MS)
        assertNotNull("title field never appeared", fields)
        fields!![0].click()
        fields[0].setText(title)
        device.findObject(By.desc(SAVE_DESCRIPTION))!!.click()
    }

    private fun ensureCalendar(device: UiDevice) {
        val create = device.wait(Until.findObject(By.text(CREATE_CALENDAR)), SHORT_TIMEOUT_MS) ?: return
        create.click()
        assertNotNull("calendar dialog missing", device.wait(Until.findObject(By.text(NEW_CALENDAR)), CONTENT_MS))
        val fields = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), CONTENT_MS)
        assertNotNull("calendar name field never appeared", fields)
        fields!![0].click()
        fields[0].setText(CALENDAR_NAME)
        device.findObject(By.text(CONFIRM_CREATE))!!.click()
        assertNotNull("no agenda after calendar", device.wait(Until.findObject(By.text(AGENDA_TAB)), CONTENT_MS))
    }

    private fun grantAllRequestedPermissions(uiAutomation: UiAutomation, pm: PackageManager, pkg: String) {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val commands = info.requestedPermissions.orEmpty().map { "pm grant $pkg $it" } +
            SPECIAL_ACCESS_COMMANDS.map { it.format(pkg) }
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
        private const val CONTENT_MS = CONTENT_TIMEOUT_MS
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val SHORT_TIMEOUT_MS = 5_000L
        private const val AGENDA_TAB = "Agenda"
        private const val TITLE_LABEL = "Title"
        private const val ADD_DESCRIPTION = "Add"
        private const val EDIT_DESCRIPTION = "Edit"
        private const val SAVE_DESCRIPTION = "Save"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        private const val ORIGINAL_TITLE = "Journey Edit Me"
        private const val UPDATED_TITLE = "Journey Edited Title"
        private const val CREATE_CALENDAR = "Create a calendar"
        private const val NEW_CALENDAR = "New local calendar"
        private const val CALENDAR_NAME = "JourneyCal"
        private const val CONFIRM_CREATE = "Create"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
