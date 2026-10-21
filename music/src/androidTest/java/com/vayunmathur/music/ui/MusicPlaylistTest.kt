package com.vayunmathur.music.ui

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
 * Playlist creation journey: open the Playlists tab, create a playlist through
 * the New Playlist dialog, and assert the new name appears in the list.
 *
 * The name is timestamped so re-runs never collide with a previous row.
 * UiDevice polling only; compose-test idle-sync is broken on current releases.
 * Cast and car flows are out of scope here (they need a TV harness).
 */
@RunWith(AndroidJUnit4::class)
class MusicPlaylistTest {

    @Test
    fun createPlaylist_appearsInList() {
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
        requireNotNull(device.wait(Until.findObject(By.text(PLAYLISTS_TAB)), CONTENT_TIMEOUT_MS)) {
            "Playlists tab missing"
        }.click()
        val playlistName = "Journey ${System.currentTimeMillis() % 100000}"
        requireNotNull(device.wait(Until.findObject(By.desc(ADD_DESC)), CONTENT_TIMEOUT_MS)) {
            "new-playlist button missing"
        }.click()
        assertNotNull(
            "create-playlist dialog never opened",
            device.wait(Until.findObject(By.text(CREATE_DIALOG_TITLE)), CONTENT_TIMEOUT_MS),
        )
        requireNotNull(device.wait(Until.findObject(By.text(NAME_HINT)), CONTENT_TIMEOUT_MS)) {
            "playlist name field missing"
        }.click()
        requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), CONTENT_TIMEOUT_MS)) {
            "playlist name field never took focus"
        }.setText(playlistName)
        requireNotNull(device.wait(Until.findObject(By.text(CREATE_BUTTON)), CONTENT_TIMEOUT_MS)) {
            "create button missing"
        }.click()
        assertNotNull(
            "new playlist never appeared in the list",
            device.wait(Until.findObject(By.text(playlistName)), CONTENT_TIMEOUT_MS),
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
            } catch (ignored: SecurityException) { // Not grantable headlessly.
            } catch (ignored: IOException) { // Pipe failed; content asserts report it.
            }
        }
        for (command in SPECIAL_ACCESS_COMMANDS) {
            try {
                uiAutomation.executeShellCommand(command.format(packageName)).close()
            } catch (ignored: SecurityException) { // Appop missing on this release.
            } catch (ignored: IOException) { // Pipe failed; gate assert reports it.
            }
        }
    }

    private companion object {
        private const val CONTENT_TIMEOUT_MS = 15_000L
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private const val PLAYLISTS_TAB = "Playlists"
        private const val ADD_DESC = "Add"
        private const val CREATE_DIALOG_TITLE = "Create Playlist"
        private const val NAME_HINT = "Playlist name"
        private const val CREATE_BUTTON = "Create"
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
