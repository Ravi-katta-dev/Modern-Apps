package com.vayunmathur.uniflix.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.uniflix.data.AddonEntity
import com.vayunmathur.uniflix.platform.AddonRow

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:uniflix`, rendered from Compose previews instead
 * of from an instrumented test on a device.
 *
 * `./gradlew :uniflix:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/uniflix/`, where `release.sh` picks them up.
 *
 * Each preview needs @PreviewTest as well as @Preview, must be a class
 * member (not top-level), and everything must be a literal — no ViewModel,
 * no database, no device.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-addons-empty", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1AddonsEmpty() {
        DynamicTheme(darkTheme = true) {
            AddonsContent(
                rows = emptyList(),
                adding = false,
                refreshing = false,
                onBack = {},
                onAddClick = {},
                onToggle = {},
                onSetDefault = {},
                onClearDefault = {},
                onMoveUp = {},
                onMoveDown = {},
                onRemove = {},
                onRefresh = {},
                onRefreshAll = {},
                onOpenDetail = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-addons-list", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2AddonsList() {
        DynamicTheme(darkTheme = true) {
            AddonsContent(
                rows = listOf(
                    AddonRow(
                        entity = AddonEntity(
                            dbId = 1,
                            manifestUrl = "https://example.com/manifest.json",
                            baseUrl = "https://example.com",
                            addonId = "example.streams",
                            name = "Example Streams",
                            version = "1.2.0",
                            enabled = true,
                            priority = 0,
                            updatedAt = 0L,
                        ),
                        isDefault = true,
                    ),
                    AddonRow(
                        entity = AddonEntity(
                            dbId = 2,
                            manifestUrl = "https://other.example/manifest.json",
                            baseUrl = "https://other.example",
                            addonId = "other.streams",
                            name = "Other Streams",
                            version = "0.9.1",
                            enabled = false,
                            priority = 1,
                            lastError = "Could not reach https://other.example",
                            updatedAt = 0L,
                        ),
                        isDefault = false,
                    ),
                ),
                adding = false,
                refreshing = false,
                onBack = {},
                onAddClick = {},
                onToggle = {},
                onSetDefault = {},
                onClearDefault = {},
                onMoveUp = {},
                onMoveDown = {},
                onRemove = {},
                onRefresh = {},
                onRefreshAll = {},
                onOpenDetail = {},
            )
        }
    }
}
