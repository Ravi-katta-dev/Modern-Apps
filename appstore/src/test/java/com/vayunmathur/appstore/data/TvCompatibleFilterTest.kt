package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TV-compat filtering: a filter through the catalogue, never a trust decision.
 *
 * On phones everything passes untouched. On TVs, explicit incompatibility and
 * phone-only hardware are hidden, while unknown (null — most sources publish no TV
 * signal) stays visible rather than emptying the store for lack of metadata.
 */
class TvCompatibleFilterTest {

    private fun app(
        tvCompatible: Boolean? = null,
        requiredFeatures: List<String> = emptyList(),
    ) = UnifiedApp(
        packageName = "com.example.app",
        source = AppSource.FDROID,
        name = "Example",
        tvCompatible = tvCompatible,
        requiredFeatures = requiredFeatures,
    )

    @Test
    fun `phone passes everything`() {
        val apps = listOf(
            app(tvCompatible = true),
            app(tvCompatible = false),
            app(tvCompatible = null),
            app(requiredFeatures = listOf("android.hardware.telephony")),
        )

        assertEquals(apps, AppProvider.filterTvCompatible(false, apps))
    }

    @Test
    fun `tv hides explicit incompatibility`() {
        val apps = listOf(app(tvCompatible = false), app(tvCompatible = true))

        assertEquals(listOf(app(tvCompatible = true)), AppProvider.filterTvCompatible(true, apps))
    }

    @Test
    fun `tv hides phone-only hardware`() {
        val apps = listOf(
            app(requiredFeatures = listOf("android.hardware.telephony")),
            app(requiredFeatures = listOf("android.software.leanback")),
        )

        val filtered = AppProvider.filterTvCompatible(true, apps)
        assertEquals(1, filtered.size)
        assertEquals(listOf("android.software.leanback"), filtered.single().requiredFeatures)
    }

    @Test
    fun `tv keeps unknown`() {
        val apps = listOf(app(tvCompatible = null))

        assertEquals(apps, AppProvider.filterTvCompatible(true, apps))
    }

    @Test
    fun `requiresPhoneHardware matches telephony`() {
        assertTrue(AppProvider.requiresPhoneHardware(listOf("android.hardware.telephony")))
        assertTrue(AppProvider.requiresPhoneHardware(listOf("android.hardware.camera.flash")))
        assertFalse(AppProvider.requiresPhoneHardware(listOf("android.software.leanback")))
        assertFalse(AppProvider.requiresPhoneHardware(emptyList()))
    }

    @Test
    fun `deriveTvCompatible marks leanback`() {
        assertEquals(
            true,
            FDroidRepository.deriveTvCompatible(
                emptyList(),
                emptyList(),
                listOf("android.software.leanback"),
            ),
        )
    }

    @Test
    fun `deriveTvCompatible marks tv category and screenshots`() {
        assertEquals(true, FDroidRepository.deriveTvCompatible(listOf("TV"), emptyList(), emptyList()))
        assertEquals(
            true,
            FDroidRepository.deriveTvCompatible(listOf("tv"), emptyList(), emptyList()),
        )
        assertEquals(
            true,
            FDroidRepository.deriveTvCompatible(
                emptyList(),
                listOf("https://f-droid.org/tv/1.png"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `deriveTvCompatible marks phone hardware incompatible`() {
        assertEquals(
            false,
            FDroidRepository.deriveTvCompatible(
                emptyList(),
                emptyList(),
                listOf("android.hardware.telephony"),
            ),
        )
    }

    @Test
    fun `deriveTvCompatible stays unknown without signals`() {
        assertNull(FDroidRepository.deriveTvCompatible(emptyList(), emptyList(), emptyList()))
        assertNull(
            FDroidRepository.deriveTvCompatible(
                listOf("Multimedia"),
                emptyList(),
                listOf("android.hardware.camera.any"),
            )
        )
    }

    @Test
    fun `authoritative index makes no signal a verdict`() {
        assertEquals(
            false,
            FDroidRepository.deriveTvCompatible(emptyList(), emptyList(), emptyList(), authoritative = true),
        )
        assertTrue(
            FDroidRepository.deriveTvCompatible(
                listOf("Modern Apps", "TV"),
                emptyList(),
                emptyList(),
                authoritative = true,
            ) == true,
        )
        assertTrue(
            FDroidRepository.deriveTvCompatible(
                emptyList(),
                emptyList(),
                listOf("android.software.leanback"),
                authoritative = true,
            ) == true,
        )
    }
}
