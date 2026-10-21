package com.vayunmathur.games.hub.ui

import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hub browse journey: launch onto the tabbed hub, open the Games tab,
 * see the games list, and open a game detail when one is registered.
 *
 * Games register via the hub provider when sibling game apps run, so a
 * fresh hub shows the stable no-games empty state instead of any card.
 * Both branches assert the screen; the detail branch additionally opens
 * the first card and checks the detail stats.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects
 * on hidden InputManager APIs that no longer exist on current releases.
 */
@RunWith(AndroidJUnit4::class)
class HubBrowseTest {

    @Test
    fun browse_openGamesTab_listVisible() {
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

        // No gate in the hub: the wall never appears and the Games
        // bottom-nav tab is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "games tab never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(GAMES_TAB_LABEL)), CONTENT_TIMEOUT_MS),
        )

        // The dashboard reuses the "Games" label for a stat card, so tap
        // the bottom-most match: the bottom-nav tab itself.
        clickBottomMost(device, GAMES_TAB_LABEL)
        assertNotNull(
            "games list never appeared",
            device.wait(Until.findObject(By.text(SEARCH_GAMES)), NAV_TIMEOUT_MS),
        )

        // A registered game opens its detail (stat row with the Last
        // label); otherwise the stable empty state is showing.
        val badge = device.wait(
            Until.findObject(By.text(INSTALLED_BADGE)),
            NAV_TIMEOUT_MS,
        ) ?: device.wait(Until.findObject(By.text(NOT_INSTALLED_BADGE)), GATE_TIMEOUT_MS)
        if (badge != null) {
            badge.click()
            assertTrue(
                "search bar still showing; game detail never opened",
                device.wait(Until.gone(By.text(SEARCH_GAMES)), NAV_TIMEOUT_MS),
            )
            val detailStats = device.wait(Until.findObject(By.text(DETAIL_LAST_LABEL)), NAV_TIMEOUT_MS)
            val missing = device.wait(Until.findObject(By.text(GAME_NOT_FOUND)), GATE_TIMEOUT_MS)
            assertTrue("game detail never appeared", detailStats != null || missing != null)
        } else {
            assertNotNull(
                "neither a game card nor the empty state is showing",
                device.wait(Until.findObject(By.textContains(EMPTY_STATE_PREFIX)), NAV_TIMEOUT_MS),
            )
        }
        assertEquals(packageName, device.currentPackageName)
    }

    private fun clickBottomMost(device: UiDevice, text: String) {
        val matches: List<UiObject2> = device.findObjects(By.text(text))
        assertTrue("no object with text \"$text\" found", matches.isNotEmpty())
        val target = matches.maxByOrNull { it.visibleBounds.centerY() }
        assertNotNull("no visible object with text \"$text\" found", target)
        target!!.click()
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
        private const val GAMES_TAB_LABEL = "Games"
        private const val SEARCH_GAMES = "Search games"
        private const val INSTALLED_BADGE = "Installed"
        private const val NOT_INSTALLED_BADGE = "Not installed"
        private const val DETAIL_LAST_LABEL = "Last"
        private const val GAME_NOT_FOUND = "Game not found"
        private const val EMPTY_STATE_PREFIX = "No games registered"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"

        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
