package com.vayunmathur.games.hub.ui

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
 * Profile journey: launch onto the tabbed hub, open the Profile tab, and
 * see the profile stats content.
 *
 * The profile always renders from local defaults (level, title, totals),
 * so the Stats section header is the stable marker.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects
 * on hidden InputManager APIs that no longer exist on current releases.
 */
@RunWith(AndroidJUnit4::class)
class HubProfileTest {

    @Test
    fun profile_openTab_statsVisible() {
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

        // No gate in the hub: the wall never appears and the Profile
        // bottom-nav tab is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        val profileTab = device.wait(Until.findObject(By.text(PROFILE_TAB_LABEL)), CONTENT_TIMEOUT_MS)
        assertNotNull(
            "profile tab never appeared; the app may have crashed on launch",
            profileTab,
        )
        profileTab!!.click()

        // The profile always renders its stats section from local data.
        assertNotNull(
            "profile stats never appeared",
            device.wait(Until.findObject(By.text(STATS_HEADER)), NAV_TIMEOUT_MS),
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
        for (permission in info.requestedPermissions.orEmpty()) {
            try {
                uiAutomation.executeShellCommand("pm grant $packageName $permission").close()
            } catch (ignored: SecurityException) {
                // Not a headlessly grantable permission: nothing to do.
            } catch (ignored: IOException) {
                // Shell pipe failed: the assertions below report it.
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
        private const val NAV_TIMEOUT_MS = 30_000L
        private const val PROFILE_TAB_LABEL = "Profile"
        private const val STATS_HEADER = "Stats"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"

        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
