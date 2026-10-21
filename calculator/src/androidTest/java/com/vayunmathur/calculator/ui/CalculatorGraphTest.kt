package com.vayunmathur.calculator.ui

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
 * Journey: Graph tab, tap Add function, a function row appears.
 * UiDevice polling, not compose-test: Espresso idle-sync is broken on API 37.
 */
@RunWith(AndroidJUnit4::class)
class CalculatorGraphTest {

    @Test
    fun graphTab_addFunction_showsFunctionRow() {
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
            "calculator keypad never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(EQUALS_KEY)), CONTENT_TIMEOUT_MS),
        )
        val graphTab = device.wait(Until.findObject(By.text(GRAPH_TAB)), CONTENT_TIMEOUT_MS)
        assertNotNull("Graph tab never appeared", graphTab)
        graphTab!!.click()
        val add = device.wait(Until.findObject(By.text(ADD_FUNCTION)), CONTENT_TIMEOUT_MS)
        assertNotNull("graph screen never opened", add)
        add!!.click()
        assertNotNull(
            "function editor never appeared",
            device.wait(Until.findObject(By.text(FUNCTION_PLACEHOLDER)), CONTENT_TIMEOUT_MS),
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
        private const val EQUALS_KEY = "="
        private const val GRAPH_TAB = "Graph"
        private const val ADD_FUNCTION = "Add function"
        private const val FUNCTION_PLACEHOLDER = "f(x)"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
