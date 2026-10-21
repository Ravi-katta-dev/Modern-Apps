package com.vayunmathur.astronomy.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.astronomy.MainActivity
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launch-no-crash test: launches the app, grants every requested permission, and
 * asserts it gets past the permission screen onto the sky map.
 *
 * Astronomy gates on FINE + COARSE location through the shared AppPermissionsGate.
 * The grant pass discovers permissions from the manifest at runtime and issues
 * headless `pm grant`s, so the gate clears without touching system UI. The sky
 * map itself is a canvas with icon-button chrome, so the test keys on the
 * Search action's content description.
 */
@RunWith(AndroidJUnit4::class)
class LaunchNoCrashTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun launch_grantsPermissions_showsSkyMap() {
        grantAllRequestedPermissions()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.waitUntil(timeoutMillis = CONTENT_TIMEOUT_MS) {
                try {
                    composeRule.onNodeWithContentDescription(SEARCH_ACTION).assertExists()
                    true
                } catch (ignored: AssertionError) {
                    false
                }
            }
            // Past the permission screen: the gate wall is gone and the sky
            // map chrome is showing.
            composeRule.onAllNodesWithText(PERMISSION_GATE_TITLE).assertCountEquals(0)
            composeRule.onNodeWithContentDescription(SEARCH_ACTION).assertIsDisplayed()
            // A crash during launch would fail the launch/wait above; the RESUMED
            // check makes the no-crash bar explicit.
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    private fun grantAllRequestedPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            targetContext.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            targetContext.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
        val requested = info.requestedPermissions ?: return
        val uiAutomation = instrumentation.uiAutomation
        for (permission in requested) {
            try {
                uiAutomation.executeShellCommand("pm grant $packageName $permission").close()
            } catch (ignored: SecurityException) {
                // Not a headlessly grantable permission: nothing to do.
            } catch (ignored: IOException) {
                // Shell pipe failed: the permission stays ungranted and the
                // content assertions below report it.
            }
        }
    }

    private companion object {
        private const val CONTENT_TIMEOUT_MS = 15_000L
        private const val SEARCH_ACTION = "Search"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
    }
}
