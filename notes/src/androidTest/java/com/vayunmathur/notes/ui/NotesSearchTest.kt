package com.vayunmathur.notes.ui

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
 * Journey: create two notes, type in the list search field, only the match stays.
 * UiDevice polling, not compose-test: Espresso idle-sync is broken on API 37.
 */
@RunWith(AndroidJUnit4::class)
class NotesSearchTest {

    @Test
    fun searchFilters_twoNotes_showsMatchOnly() {
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
            "notes list never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(SEARCH_PLACEHOLDER)), CONTENT_TIMEOUT_MS),
        )

        createNote(device, ALPHA_TITLE)
        createNote(device, BETA_TITLE)

        val search = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), CONTENT_TIMEOUT_MS)
        assertNotNull("search field never appeared", search)
        search!![0].click()
        search[0].setText(QUERY)

        assertNotNull(
            "matching note never appeared",
            device.wait(Until.findObject(By.text(BETA_TITLE)), CONTENT_TIMEOUT_MS),
        )
        assertNull(
            "non-matching note is still listed; search did not filter",
            device.wait(Until.findObject(By.text(ALPHA_TITLE)), SHORT_TIMEOUT_MS),
        )
        assertEquals(packageName, device.currentPackageName)
    }

    private fun createNote(device: UiDevice, title: String) {
        device.findObject(By.desc(ADD_DESCRIPTION))!!.click()
        assertNotNull(
            "note editor never opened",
            device.wait(Until.findObject(By.text(EDITOR_TITLE_HINT)), CONTENT_TIMEOUT_MS),
        )
        val fields = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), CONTENT_TIMEOUT_MS)
        assertNotNull("editor fields never appeared", fields)
        fields!![0].click()
        fields[0].setText(title)
        device.pressBack()
        assertNotNull(
            "created note is not listed",
            device.wait(Until.findObject(By.text(title)), CONTENT_TIMEOUT_MS),
        )
    }

    private fun grantAllRequestedPermissions(
        uiAutomation: UiAutomation,
        packageManager: PackageManager,
        packageName: String,
    ) {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
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
        private const val SEARCH_PLACEHOLDER = "Notes"
        private const val EDITOR_TITLE_HINT = "Title"
        private const val ADD_DESCRIPTION = "Add"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        private const val ALPHA_TITLE = "Journey Search Alpha"
        private const val BETA_TITLE = "Journey Search Beta"
        private const val QUERY = "Beta"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
