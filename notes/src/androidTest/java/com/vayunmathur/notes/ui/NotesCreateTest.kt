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
 * Journey: FAB into the editor, type title plus body, back out, list shows it.
 * UiDevice polling, not compose-test: Espresso idle-sync is broken on API 37.
 */
@RunWith(AndroidJUnit4::class)
class NotesCreateTest {

    @Test
    fun createNote_backToList_showsNote() {
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

        // The Add icon itself is not clickable — its ancestor is.
        val fab = device.wait(
            Until.findObject(By.clickable(true).hasDescendant(By.desc(ADD_DESCRIPTION))),
            SHORT_TIMEOUT_MS,
        )
        assertNotNull("create-note FAB never appeared", fab)
        fab!!.click()
        assertNotNull(
            "note editor never opened",
            device.wait(Until.findObject(By.text(EDITOR_TITLE_HINT)), CONTENT_TIMEOUT_MS),
        )

        val fields = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), CONTENT_TIMEOUT_MS)
        assertNotNull("editor fields never appeared", fields)
        fields!![0].click()
        fields[0].setText(NOTE_TITLE)
        if (fields.size > 1) {
            fields[1].click()
            fields[1].setText(NOTE_BODY)
        }
        device.pressBack()

        assertNotNull(
            "created note is not listed",
            device.wait(Until.findObject(By.text(NOTE_TITLE)), CONTENT_TIMEOUT_MS),
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
        private const val SEARCH_PLACEHOLDER = "Notes"
        private const val EDITOR_TITLE_HINT = "Title"
        private const val ADD_DESCRIPTION = "Add"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        private const val NOTE_TITLE = "Journey Note Alpha"
        private const val NOTE_BODY = "Alpha body text"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
