package com.vayunmathur.music.ui

import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
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
 * Now-playing journey: tap a real library row and assert the full player
 * paints with its transport controls.
 *
 * Relies on seeded device data (two MP3s indexed in MediaStore); the test
 * reads the first audio title straight from MediaStore so it never hardcodes
 * a song name. The row tap plays under the All Songs source, so the player
 * offers the "Go to All Songs" jump alongside Pause. UiDevice polling only;
 * compose-test idle-sync is broken on current releases. Cast and car flows are
 * out of scope here (they need a TV harness) and are not touched.
 */
@RunWith(AndroidJUnit4::class)
class MusicNowPlayingTest {

    @Test
    fun playFirstSong_nowPlayingControlsVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName
        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)
        val songTitle = firstAudioTitle() ?: error("no audio in MediaStore; seed data missing")
        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "library tabs never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(ALBUMS_TAB_LABEL)), CONTENT_TIMEOUT_MS),
        )
        requireNotNull(device.wait(Until.findObject(By.text(songTitle)), CONTENT_TIMEOUT_MS)) {
            "seeded song missing from the Songs list"
        }.click()
        assertNotNull(
            "now-playing never opened after tapping the song",
            device.wait(Until.findObject(By.desc(PAUSE_DESC)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "source jump missing; playback is not under All Songs",
            device.wait(Until.findObject(By.text(GO_TO_ALL_SONGS)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "skip-next control missing from the player",
            device.wait(Until.findObject(By.desc(NEXT_DESC)), CONTENT_TIMEOUT_MS),
        )
        assertEquals(packageName, device.currentPackageName)
    }

    private fun firstAudioTitle(): String? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projection = arrayOf(MediaStore.Audio.Media.TITLE)
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.TITLE} ASC",
        )?.use { cursor ->
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            while (cursor.moveToNext()) {
                val title = cursor.getString(titleColumn)
                if (!title.isNullOrBlank()) return title
            }
        }
        return null
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
        private const val ALBUMS_TAB_LABEL = "Albums"
        private const val PAUSE_DESC = "Pause"
        private const val NEXT_DESC = "Next"
        private const val GO_TO_ALL_SONGS = "Go to All Songs"
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
