package com.vayunmathur.taxi.ui

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
 * Journey: walk the bottom tabs from ride to current ride to settings, and
 * assert each tab paints its content.
 *
 * While signed out the current-ride tab shows its empty message and the
 * settings tab shows payment methods, so those markers prove each navigation
 * landed.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class TaxiTabsTest {

    @Test
    fun tabs_navigateToEachDestination() {
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
        // ride tab label is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "ride tab never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(RIDE_TAB_LABEL)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "destination field never appeared inside the ride tab",
            device.wait(Until.findObject(By.text(DESTINATION_PLACEHOLDER)), CONTENT_TIMEOUT_MS),
        )

        // Current-ride tab paints its empty message while signed out.
        val currentRide = device.wait(Until.findObject(By.text(CURRENT_RIDE_TAB)), GATE_TIMEOUT_MS)
        assertNotNull("current-ride tab never appeared", currentRide)
        currentRide!!.click()
        assertNotNull(
            "current-ride content never appeared after tapping the tab",
            device.wait(Until.findObject(By.text(NO_CURRENT_RIDE)), CONTENT_TIMEOUT_MS),
        )

        // Settings tab paints payment methods.
        val settings = device.wait(Until.findObject(By.text(SETTINGS_TAB)), GATE_TIMEOUT_MS)
        assertNotNull("settings tab never appeared", settings)
        settings!!.click()
        assertNotNull(
            "settings content never appeared after tapping the tab",
            device.wait(Until.findObject(By.text(PAYMENT_METHODS)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash while navigating.
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
        private const val RIDE_TAB_LABEL = "Ride"
        private const val CURRENT_RIDE_TAB = "Current ride"
        private const val SETTINGS_TAB = "Settings"
        private const val DESTINATION_PLACEHOLDER = "Where to?"
        private const val NO_CURRENT_RIDE = "No ride in progress"
        private const val PAYMENT_METHODS = "Payment methods"
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
