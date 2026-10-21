package com.vayunmathur.things.ui

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
 * Add-device journey: from home open the Devices screen and start a bottle
 * scan, asserting the device picker dialog renders.
 *
 * The picker scans headlessly; nearby hardware decides whether it lists
 * entries, so the stable marker is the dialog title rather than any row.
 * Pairing itself needs physical hardware, so the journey stops at the open
 * picker and dismisses it back to the Devices screen.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class ThingsAddTest {

    @Test
    fun openDevices_startBottleScan_showsDevicePicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the
        // home empty state is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "home empty state never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(HOME_EMPTY_STATE)), CONTENT_TIMEOUT_MS),
        )

        val addDevice = device.wait(Until.findObject(By.text(ADD_DEVICE_BUTTON)), CONTENT_TIMEOUT_MS)
        assertNotNull("Add device entry never appeared on home", addDevice)
        addDevice!!.click()

        // The Devices screen offers one entry per supported device type.
        val bottle = device.wait(Until.findObject(By.text(CONNECT_BOTTLE)), CONTENT_TIMEOUT_MS)
        assertNotNull("Devices screen never opened", bottle)
        assertNotNull(
            "scale entry never appeared on the Devices screen",
            device.wait(Until.findObject(By.text(CONNECT_SCALE)), SHORT_TIMEOUT_MS),
        )
        bottle!!.click()

        assertNotNull(
            "device picker dialog never opened after starting the bottle scan",
            device.wait(Until.findObject(By.text(PICKER_TITLE)), CONTENT_TIMEOUT_MS),
        )
        device.pressBack()
        assertNotNull(
            "did not return to the Devices screen after dismissing the picker",
            device.wait(Until.findObject(By.text(CONNECT_BOTTLE)), CONTENT_TIMEOUT_MS),
        )
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
        private const val HOME_EMPTY_STATE = "No devices added"
        private const val ADD_DEVICE_BUTTON = "Add device"
        private const val CONNECT_BOTTLE = "Connect new water bottle"
        private const val CONNECT_SCALE = "Connect new scale"
        private const val PICKER_TITLE = "Select a device"
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
