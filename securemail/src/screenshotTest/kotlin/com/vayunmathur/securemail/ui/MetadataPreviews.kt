package com.vayunmathur.securemail.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.securemail.platform.MailUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store-listing images for `:securemail`, rendered from Compose previews.
 *
 * `./gradlew :securemail:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/securemail/`, where `release.sh` picks them up.
 *
 * Each preview must carry @PreviewTest as well as @Preview and be a member of a class
 * (not a top-level function) or the screenshot engine silently skips it. Everything is
 * a literal so the output is reproducible from a clean checkout.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-onboarding", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Onboarding() {
        DynamicTheme(darkTheme = true) {
            OnboardingScreen(
                state = MailUiState(),
                onClaim = { _, _ -> },
                onDismissMessages = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-inbox", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Inbox() {
        DynamicTheme(darkTheme = true) {
            InboxScreen(
                state = MailUiState(
                    address = "alice@maemail.org",
                    socketConnected = true,
                ),
                folder = com.vayunmathur.securemail.data.MailFolder.INBOX,
                onOpenDrawer = {},
                onOpen = {},
                onCompose = {},
                onSettings = {},
                onRefresh = {},
                onConsumeMessage = {},
                onTrash = {},
                onRestore = {},
                onEmptyTrash = {},
                messages = listOf(
                    com.vayunmathur.securemail.data.StoredMessage(
                        id = "m_1",
                        from = "bob@proton.me",
                        toJson = "alice@maemail.org",
                        dateMillis = 1_792_772_800_000L,
                        messageId = "m_1",
                        blobBase64 = "",
                        decryptedBody = "Lunch tomorrow?",
                        decryptedSubject = "Hello from Proton",
                        verifiedSigner = "A1B2C3D4E5F60718",
                    ),
                    com.vayunmathur.securemail.data.StoredMessage(
                        id = "m_2",
                        from = "carol@maemail.org",
                        toJson = "alice@maemail.org",
                        dateMillis = 1_792_770_000_000L,
                        messageId = "m_2",
                        blobBase64 = "e30=",
                    ),
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-compose", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Compose() {
        DynamicTheme(darkTheme = true) {
            ComposeScreen(
                state = MailUiState(address = "alice@maemail.org"),
                draftTo = listOf("bob@proton.me"),
                draftCc = emptyList(),
                draftBcc = emptyList(),
                initialSubject = "Hello from SecureMail",
                initialBody = "This message is end-to-end encrypted with PGP.",
                onAddRecipient = { _, _ -> true },
                onRemoveRecipient = { _, _ -> },
                onSend = { _, _, _, _, _, _ -> },
                onBack = {},
            )
        }
    }
}
