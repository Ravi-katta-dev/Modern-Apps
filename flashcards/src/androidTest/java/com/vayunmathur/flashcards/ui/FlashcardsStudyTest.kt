package com.vayunmathur.flashcards.ui

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
 * Study journey: create a deck, add a card to it, open the study session,
 * reveal the answer, and grade the card.
 *
 * Every step runs headlessly: the deck dialog, the note editor save action,
 * and the review grade buttons are all plain on-screen controls. A fresh card
 * counts as due, so the Study button appears without any scheduling setup.
 * Names are unique per journey so earlier runs cannot collide.
 *
 * After grading the only card the session usually reports "All caught up!";
 * the fallback accepts an advanced card instead, since scheduling decides
 * which of the two renders. Either way the flip plus grade round trip ran.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class FlashcardsStudyTest {

    @Test
    fun createDeck_addCard_study_flipAndGrade() {
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
        // decks tab is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "decks tab never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(DECKS_TAB)), CONTENT_TIMEOUT_MS),
        )

        // A deck to study in: FAB into the dialog, type the name, confirm.
        val deckFab = device.wait(Until.findObject(By.desc(ADD_DESCRIPTION)), SHORT_TIMEOUT_MS)
        assertNotNull("create-deck FAB never appeared", deckFab)
        deckFab!!.click()
        assertNotNull(
            "new-deck dialog never opened",
            device.wait(Until.findObject(By.text(NEW_DECK_TITLE)), CONTENT_TIMEOUT_MS),
        )
        val nameFields = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), CONTENT_TIMEOUT_MS)
        assertNotNull("deck name field never appeared", nameFields)
        nameFields!![0].click()
        nameFields[0].setText(DECK_NAME)
        val confirmDeck = device.wait(Until.findObject(By.text(ADD_CONFIRM)), SHORT_TIMEOUT_MS)
        assertNotNull("deck dialog confirm never appeared", confirmDeck)
        confirmDeck!!.click()
        val deckRow = device.wait(Until.findObject(By.text(DECK_NAME)), CONTENT_TIMEOUT_MS)
        assertNotNull("created deck is not listed", deckRow)
        deckRow!!.click()

        // A fresh deck holds no cards, which is the deterministic entry marker.
        assertNotNull(
            "deck card list never opened",
            device.wait(Until.findObject(By.text(NO_CARDS)), CONTENT_TIMEOUT_MS),
        )
        val cardFab = device.wait(Until.findObject(By.desc(ADD_DESCRIPTION)), SHORT_TIMEOUT_MS)
        assertNotNull("add-card FAB never appeared", cardFab)
        cardFab!!.click()
        assertNotNull(
            "card editor never opened",
            device.wait(Until.findObject(By.text(NEW_CARD_TITLE)), CONTENT_TIMEOUT_MS),
        )
        val cardFields = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), CONTENT_TIMEOUT_MS)
        assertNotNull("card fields never appeared", cardFields)
        assertTrue("card editor has no front and back fields", cardFields!!.size > 1)
        cardFields[0].click()
        cardFields[0].setText(FRONT_TEXT)
        cardFields[1].click()
        cardFields[1].setText(BACK_TEXT)
        val save = device.wait(Until.findObject(By.desc(SAVE_DESCRIPTION)), SHORT_TIMEOUT_MS)
        assertNotNull("card save action never appeared", save)
        save!!.click()
        assertNotNull(
            "saved card never appeared in the deck",
            device.wait(Until.findObject(By.text(FRONT_TEXT)), CONTENT_TIMEOUT_MS),
        )

        // The fresh card is due, so Study is offered; flip it and grade it.
        val study = device.wait(Until.findObject(By.text(STUDY_BUTTON)), CONTENT_TIMEOUT_MS)
        assertNotNull("study button never appeared for the new card", study)
        study!!.click()
        assertNotNull(
            "study card never appeared",
            device.wait(Until.findObject(By.text(FRONT_TEXT)), CONTENT_TIMEOUT_MS),
        )
        val showAnswer = device.wait(Until.findObject(By.text(SHOW_ANSWER)), CONTENT_TIMEOUT_MS)
        assertNotNull("show-answer button never appeared", showAnswer)
        showAnswer!!.click()
        val good = device.wait(Until.findObject(By.text(GRADE_GOOD)), CONTENT_TIMEOUT_MS)
        assertNotNull("grade buttons never appeared after revealing", good)
        good!!.click()
        val done = device.wait(Until.findObject(By.text(REVIEW_DONE)), CONTENT_TIMEOUT_MS)
        if (done == null) {
            assertNotNull(
                "review neither finished nor advanced after grading",
                device.wait(Until.findObject(By.text(SHOW_ANSWER)), SHORT_TIMEOUT_MS),
            )
        }
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
        private const val DECKS_TAB = "Decks"
        private const val NEW_DECK_TITLE = "New deck"
        private const val ADD_DESCRIPTION = "Add"
        private const val ADD_CONFIRM = "Add"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        private const val DECK_NAME = "Journey Study Alpha"
        private const val NO_CARDS = "No cards yet"
        private const val NEW_CARD_TITLE = "New card"
        private const val SAVE_DESCRIPTION = "Save"
        private const val FRONT_TEXT = "Journey Front Alpha"
        private const val BACK_TEXT = "Journey Back Alpha"
        private const val STUDY_BUTTON = "Study"
        private const val SHOW_ANSWER = "Show answer"
        private const val GRADE_GOOD = "Good"
        private const val REVIEW_DONE = "All caught up!"
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
