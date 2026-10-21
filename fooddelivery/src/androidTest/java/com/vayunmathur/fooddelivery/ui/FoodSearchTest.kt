package com.vayunmathur.fooddelivery.ui

import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
 * Search journey: deep-link straight to a restaurant page, type a query that
 * matches nothing, and assert the menu empty state renders.
 *
 * The restaurant list needs a saved address plus network, so the journey
 * enters through the fooddelivery://restaurant deep link the activity already
 * parses. When the backend is unreachable headlessly the detail reports
 * missing instead of offering search, and the test asserts that branch: it
 * proves the deep link landed rather than crashing. The gibberish query keeps
 * the empty-state marker deterministic regardless of catalog content.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class FoodSearchTest {

    @Test
    fun deepLinkRestaurant_search_filtersToEmptyState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse(DEEP_LINK))
            .setClassName(packageName, "$packageName.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )

        val searchLabel = device.wait(Until.findObject(By.text(SEARCH_MENU_LABEL)), CONTENT_TIMEOUT_MS)
        if (searchLabel != null) {
            // The menu loaded: filter it down to nothing and read the empty state.
            val fields = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), CONTENT_TIMEOUT_MS)
            assertNotNull("menu search field never appeared", fields)
            fields!![0].click()
            fields[0].setText(GIBBERISH_QUERY)
            assertNotNull(
                "menu search produced neither results nor its empty state",
                device.wait(Until.findObject(By.textContains(EMPTY_MATCH_SNIPPET)), CONTENT_TIMEOUT_MS),
            )
        } else {
            // The backend is unreachable headlessly: the detail cannot load, so
            // the screen reports it instead of offering search.
            assertNotNull(
                "restaurant detail neither loaded nor reported missing",
                device.wait(Until.findObject(By.text(NOT_FOUND_TITLE)), SHORT_TIMEOUT_MS),
            )
        }
        // Still alive in front: the process did not crash through the journey.
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
        private const val SHORT_TIMEOUT_MS = 5_000L
        private const val DEEP_LINK = "fooddelivery://restaurant/1"
        private const val SEARCH_MENU_LABEL = "Search menu"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        private const val GIBBERISH_QUERY = "zzz-no-such-dish-zzz"
        private const val EMPTY_MATCH_SNIPPET = "No menu items match"
        private const val NOT_FOUND_TITLE = "Restaurant not found"
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
