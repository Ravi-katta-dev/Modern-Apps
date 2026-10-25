package com.vayunmathur.tuner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconGrid
import com.vayunmathur.library.ui.IconMusicNote
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.tuner.data.AnalysisRange
import com.vayunmathur.tuner.data.Instrument
import com.vayunmathur.tuner.data.InstrumentKind
import com.vayunmathur.tuner.data.KeyboardSpec
import com.vayunmathur.tuner.data.StringSpec
import com.vayunmathur.tuner.domain.ChordCandidate
import com.vayunmathur.tuner.domain.ChordLabel
import com.vayunmathur.tuner.domain.ChordName
import com.vayunmathur.tuner.domain.ChordPresentation
import com.vayunmathur.tuner.domain.ChordQuality
import com.vayunmathur.tuner.domain.ChordReading
import com.vayunmathur.tuner.domain.ChordTier
import com.vayunmathur.tuner.domain.DetectedNote
import com.vayunmathur.tuner.domain.TuningBand
import com.vayunmathur.tuner.domain.Voicing
import com.vayunmathur.tuner.platform.ChordUiState
import com.vayunmathur.tuner.platform.NoteReadout
import com.vayunmathur.tuner.platform.NoteUiState
import com.vayunmathur.tuner.platform.TunerUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi - comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Desktop-wide (1280dp >= 840dp), so the letterboxed expanded layout renders. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store-listing images for `:tuner`, rendered from Compose previews.
 *
 * An instrumented test is not an option here: the screens are driven by live microphone audio,
 * and there is no way to feed a device a guitar. Literal state is the only reproducible source,
 * which is exactly why the screens are stateless.
 *
 * Note and Chord tabs render inside the real tab chrome ([ChromePreview]); only the
 * selected tab is literal.
 */
class MetadataPreviews {

    private val guitar = Instrument(
        id = "guitar-standard",
        familyId = "guitar",
        kind = InstrumentKind.FRETTED,
        strings = listOf(
            StringSpec(40, "E"),
            StringSpec(45, "A"),
            StringSpec(50, "D"),
            StringSpec(55, "G"),
            StringSpec(59, "B"),
            StringSpec(64, "E"),
        ),
        fretCount = 15,
        analysis = AnalysisRange(70.0, 1400.0),
        isDefaultForFamily = true,
    )

    private val piano = Instrument(
        id = "piano",
        familyId = "piano",
        kind = InstrumentKind.KEYBOARD,
        keyboard = KeyboardSpec(21, 108),
        analysis = AnalysisRange(27.0, 4200.0),
        capoSupported = false,
        isDefaultForFamily = true,
    )

    private fun noteState(
        instrument: Instrument,
        readout: NoteReadout,
    ) = TunerUiState(
        instrument = instrument,
        instruments = listOf(guitar, piano),
        listening = true,
        note = NoteUiState(readout = readout),
    )

    @PreviewTest
    @Preview(name = "1-note-in-tune", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1NoteInTune() {
        ChromePreview(selectedTab = 0) {
            NoteScreen(
                state = noteState(
                    guitar,
                    NoteReadout(
                        midi = 40,
                        letter = "E",
                        octave = 2,
                        frequencyHz = 82.44,
                        cents = 0.7,
                        band = TuningBand.IN_TUNE,
                    ),
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-note-flat", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2NoteFlat() {
        ChromePreview(selectedTab = 0) {
            NoteScreen(
                state = noteState(
                    guitar,
                    NoteReadout(
                        midi = 55,
                        letter = "G",
                        octave = 3,
                        frequencyHz = 195.32,
                        cents = -6.0,
                        band = TuningBand.OUT,
                    ),
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-note-piano-reference", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3NotePianoReference() {
        ChromePreview(selectedTab = 0) {
            NoteScreen(
                state = noteState(
                    piano,
                    NoteReadout(
                        midi = 69,
                        letter = "A",
                        octave = 4,
                        frequencyHz = 441.6,
                        cents = 2.3,
                        band = TuningBand.CLOSE,
                    ),
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-note-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview4NoteExpanded() {
        ChromePreview(selectedTab = 0) {
            NoteScreen(
                state = noteState(
                    guitar,
                    NoteReadout(
                        midi = 40,
                        letter = "E",
                        octave = 2,
                        frequencyHz = 82.44,
                        cents = 0.7,
                        band = TuningBand.IN_TUNE,
                    ),
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-chord-g-major", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview5ChordGMajor() {
        // A strummed open-G shape, confidently named: G major (G3+B3+D4) with the
        // recommended open voicing, so the diagram pager has its primary page.
        ChromePreview(selectedTab = 1) {
            ChordScreen(
                state = noteState(guitar, NoteReadout(midi = 55, letter = "G", octave = 3, frequencyHz = 196.0, cents = 1.2, band = TuningBand.IN_TUNE)).copy(
                    chord = ChordUiState(
                        reading = ChordReading(
                            notes = listOf(
                                DetectedNote(midi = 55, salience = 0.9),
                                DetectedNote(midi = 59, salience = 0.75),
                                DetectedNote(midi = 62, salience = 0.7),
                            ),
                            bass = 55,
                            bassConfidence = 0.85f,
                            label = ChordLabel.Chord(ChordName(root = 55, quality = ChordQuality.MAJOR)),
                            alternates = listOf(
                                ChordCandidate(ChordName(root = 64, quality = ChordQuality.MAJOR), 0.31f),
                            ),
                            confidence = 0.92f,
                            tier = ChordTier.CONFIRMED,
                            presentation = ChordPresentation.CONFIDENT,
                        ),
                        voicings = listOf(
                            Voicing(
                                frets = listOf(3, 2, 0, 0, 0, 3),
                                barreFret = null,
                                fingerCount = 3,
                                soundingMidi = listOf(55, 59, 62, 67, 71, 55),
                                cost = 0.0,
                                isPrimary = true,
                            ),
                        ),
                    ),
                ),
                onSelectInstrument = {},
            )
        }
    }

    /**
     * The app's real tab chrome around preview screen content: the same
     * [TabbedPagerScaffold] with the same Note/Chord tabs `TunerTabs` declares.
     * Only the selected tab is literal.
     */
    @Composable
    private fun ChromePreview(selectedTab: Int, content: @Composable () -> Unit) {
        DynamicTheme(darkTheme = true) {
            val tabs = listOf(
                PagerTab("Note", { IconMusicNote() }) {
                    if (selectedTab == 0) content() else Box(Modifier.fillMaxSize())
                },
                PagerTab("Chord", { IconGrid() }) {
                    if (selectedTab == 1) content() else Box(Modifier.fillMaxSize())
                },
            )
            TabbedPagerScaffold(
                tabs = tabs,
                pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { tabs.size }),
                tabStyle = TabStyle.BottomNav,
            )
        }
    }
}
