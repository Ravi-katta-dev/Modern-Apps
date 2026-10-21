package com.vayunmathur.contacts.ui

import android.app.UiAutomation
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
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
 * Journey: read a seeded device contact, type its first name into search, and
 * assert the contact detail opens.
 *
 * The name comes from the contacts provider at runtime, so the test keys on
 * the seeded data itself instead of a hardcoded string. Observed on device:
 * typing a uniquely-matching name opens the detail page directly (Call /
 * Message actions + the seeded phone number), so the test asserts that
 * end-to-end path rather than a filtered row.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class ContactsSearchTest {

    @Test
    fun search_seededName_showsRow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(
            instrumentation.uiAutomation,
            targetContext.packageManager,
            packageName,
        )

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the
        // contacts tabs are showing (first paint is the tab row, not the
        // search field — verified on device via uiautomator dump).
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "contacts list never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(CONTACTS_TAB)), CONTENT_TIMEOUT_MS),
        )

        // Seeded query: the contact's phone number for the detail
        // assertion, first name token typed into search.
        val seeded = readSeededContact(targetContext.contentResolver)
        assertNotNull("no seeded contact found on the device", seeded)
        val phone = seeded!!.first
        val token = seeded.second

        // The search field is part of first paint (proven by LaunchNoCrashTest):
        // tap it directly. Do NOT tap the Search action icon — that toggles
        // search UI state and collapses the field we need. Note the field is
        // an EditText with EMPTY text: the "Search contacts" Compose hint
        // never reaches the accessibility tree, so key on the class.
        val searchField = device.wait(Until.findObject(By.clazz(EDIT_TEXT_CLASS)), GATE_TIMEOUT_MS)
        assertNotNull("search field never appeared", searchField)
        searchField!!.click()
        searchField.setText(token)
        // Commit the query through the IME: some fields only propagate the
        // filter on an editor action rather than on raw text change.
        searchField.click()
        device.pressEnter()

        // A uniquely-matching query opens the detail page directly (Call /
        // Message actions plus the seeded number) instead of filtering the
        // list. Cold-start sync (provider + SIM + aggregation) can take ~20s,
        // so the waits are generous; verified on device via uiautomator dumps.
        assertNotNull(
            "contact detail never opened for the search query",
            device.wait(Until.findObject(By.text(phone)), ROW_TIMEOUT_MS),
        )
        assertNotNull(
            "detail Call action missing",
            device.wait(Until.findObject(By.text(CALL_ACTION)), ROW_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after searching.
        assertEquals(packageName, device.currentPackageName)
    }

    /**
     * First seeded contact with a phone number: number plus the first long
     * name token, which is what gets typed into search.
     */
    private fun readSeededContact(resolver: ContentResolver): Pair<String, String>? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty().trim() else ""
                val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty().trim() else ""
                val token = name.split(' ').firstOrNull { it.length >= 2 }.orEmpty()
                if (number.isNotEmpty() && token.isNotEmpty()) return Pair(number, token)
            }
        }
        return null
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
        private const val ROW_TIMEOUT_MS = 60_000L
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val CONTACTS_TAB = "Contacts"
        private const val CALL_ACTION = "Call"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
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
