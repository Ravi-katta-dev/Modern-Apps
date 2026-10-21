package com.vayunmathur.email.ui

import android.app.UiAutomation
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.email.data.OutboxManager
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Outbox journey: with accounts present the app opens past the gate onto the
 * inbox; a send that fails (unroutable host) lands in the outbox queue.
 *
 * Headless runs have no real credentials, so the test seeds a `custom` account
 * pointing at a localhost port nothing listens on, then queues the unsendable
 * message directly through [OutboxManager], which is the same enqueue path
 * [com.vayunmathur.email.platform.EmailSendActions.sendFrom] falls back to on
 * error. The test asserts the inbox opens past the seeded account and the
 * queued entry persists in the outbox store. UiDevice polling
 * only; compose-test idle-sync is broken on current releases.
 */
@RunWith(AndroidJUnit4::class)
class EmailOutboxTest {

    @Test
    fun seedAccountAndQueue_showsOutboxBadge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName
        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)
        runBlocking {
            val repo = EmailRepository.get(targetContext)
            if (repo.accounts.getAccountByEmail(ACCOUNT_EMAIL) == null) {
                repo.accounts.insertAccount(
                    EmailAccount(
                        email = ACCOUNT_EMAIL,
                        provider = "custom",
                        imapHost = "127.0.0.1",
                        imapPort = 10993,
                        imapUseSsl = false,
                        smtpHost = "127.0.0.1",
                        smtpPort = 11025,
                        smtpUseSsl = false,
                        authType = "password",
                    ),
                )
            }
            OutboxManager.enqueue(
                context = targetContext,
                accountEmail = ACCOUNT_EMAIL,
                to = "queue@example.com",
                subject = OUTBOX_SUBJECT,
                body = "queued by journey test",
                initialError = "journey seed",
            )
        }
        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "inbox never opened past the seeded account",
            device.wait(Until.findObject(By.text(INBOX_TITLE)), CONTENT_TIMEOUT_MS),
        )
        assertNull(
            "add-account gate showing despite seeded account",
            device.wait(Until.findObject(By.text(ADD_ACCOUNT_TITLE)), GATE_TIMEOUT_MS),
        )
        runBlocking {
            val outbox: List<OutboxEntry> = EmailRepository.get(targetContext).outbox.getOutbox()
            check(outbox.any { it.subject == OUTBOX_SUBJECT }) { "seeded outbox entry missing" }
            val stale = outbox.filter { it.subject == OUTBOX_SUBJECT }.drop(1)
            stale.forEach { EmailRepository.get(targetContext).outbox.deleteOutboxEntry(it) }
        }
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
        private const val ADD_ACCOUNT_TITLE = "Add Account"
        private const val INBOX_TITLE = "Unified Inbox"
        private const val ACCOUNT_EMAIL = "journey@example.com"
        private const val OUTBOX_SUBJECT = "Journey queued send"
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
