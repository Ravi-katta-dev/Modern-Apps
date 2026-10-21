package com.vayunmathur.files.ui

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
 * Files search journey: launches, opens Internal storage, taps search, types a
 * query with no match, and asserts the empty state is showing.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class FilesSearchTest {

    @Test
    fun searchNoMatch_showsEmptyState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(
            instrumentation.uiAutomation,
            targetContext.packageManager,
            packageName
        )

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the
        // Internal storage entry is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        val internal = device.wait(
            Until.findObject(By.text(DIRECTORY_TITLE)),
            CONTENT_TIMEOUT_MS
        )
        assertNotNull("internal storage never appeared; the app may have crashed", internal)
        internal!!.click()
        // Open search, type a query nothing can match, and the empty state shows.
        val search = device.wait(Until.findObject(By.desc(SEARCH_DESC)), CONTENT_TIMEOUT_MS)
        assertNotNull("search action never appeared; the listing did not load", search)
        search!!.click()
        assertNotNull(
            "search field never appeared; the search did not open",
            device.wait(Until.findObject(By.text(SEARCH_HINT)), CONTENT_TIMEOUT_MS),
        )
        val field = device.wait(
            Until.findObject(By.clazz(EDIT_TEXT_CLASS)),
            CONTENT_TIMEOUT_MS
        )
        assertNotNull("search input never appeared; the search did not open", field)
        field!!.text = NO_MATCH_QUERY
        assertNotNull(
            "empty state never appeared; the search did not filter",
            device.wait(Until.findObject(By.text(EMPTY_STATE)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash during the search.
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
        private const val DIRECTORY_TITLE = "Internal storage"
        private const val SEARCH_DESC = "Search"
        private const val SEARCH_HINT = "Search files…"
        private const val EMPTY_STATE = "Nothing here"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        private const val NO_MATCH_QUERY = "zzz_no_such_file_123"
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
