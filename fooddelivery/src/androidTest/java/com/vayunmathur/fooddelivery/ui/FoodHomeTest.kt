package com.vayunmathur.fooddelivery.ui

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home journey: launch onto the tab host, hop to the cart tab and back, and
 * assert both tabs settle on deterministic content.
 *
 * The cart is empty on a fresh install with no network involved, so its empty
 * state is the stable marker. Home without a saved default address shows the
 * address prompt; with no reachable merchants it shows the fetch empty state
 * instead. A saved address plus network would show the merchant grid, which
 * has no stable text marker and is therefore out of headless scope.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class FoodHomeTest {

    @Test
    fun launch_tabsNavigate_homeAndCartRender() {
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
        // Home bottom-nav tab is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "home tab never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(HOME_TAB_LABEL)), CONTENT_TIMEOUT_MS),
        )

        // The cart tab needs no account or network: the empty cart renders.
        val cartTab = device.wait(Until.findObject(By.text(CART_TAB_LABEL)), SHORT_TIMEOUT_MS)
        assertNotNull("cart tab never appeared", cartTab)
        cartTab!!.click()
        assertNotNull(
            "empty cart never appeared",
            device.wait(Until.findObject(By.text(CART_EMPTY_TITLE)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "empty-cart hint never appeared",
            device.wait(Until.findObject(By.text(CART_EMPTY_HINT)), SHORT_TIMEOUT_MS),
        )

        // Back home: the address prompt or the fetch empty state settles.
        val homeTab = device.wait(Until.findObject(By.text(HOME_TAB_LABEL)), SHORT_TIMEOUT_MS)
        assertNotNull("did not return to the home tab", homeTab)
        homeTab!!.click()
        val noAddress = device.wait(Until.findObject(By.text(SET_ADDRESS_TITLE)), CONTENT_TIMEOUT_MS)
        val noMerchants = if (noAddress == null) {
            device.wait(Until.findObject(By.text(NO_RESTAURANTS_TITLE)), SHORT_TIMEOUT_MS)
        } else {
            null
        }
        assertTrue(
            "home content never settled on an address prompt or an empty merchant list",
            noAddress != null || noMerchants != null,
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
        private const val HOME_TAB_LABEL = "Home"
        private const val CART_TAB_LABEL = "Cart"
        private const val CART_EMPTY_TITLE = "Your cart is empty"
        private const val CART_EMPTY_HINT = "Add items from a restaurant"
        private const val SET_ADDRESS_TITLE = "Set your address"
        private const val NO_RESTAURANTS_TITLE = "No restaurants found"
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
