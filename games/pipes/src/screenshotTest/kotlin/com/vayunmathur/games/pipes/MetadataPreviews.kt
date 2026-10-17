package com.vayunmathur.games.pipes

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.RenderPos
import com.vayunmathur.games.pipes.ui.GameBoardScreen
import com.vayunmathur.games.pipes.ui.PackListScreen
import com.vayunmathur.games.pipes.ui.PipesTheme
import com.vayunmathur.games.pipes.platform.GameBoardUiState
import com.vayunmathur.games.pipes.platform.PackProgress
import com.vayunmathur.games.pipes.platform.PipesActions
import com.vayunmathur.games.pipes.platform.PipesGameState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:games:pipes`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * The levels below are transcribed from the first levels of the bundled packs
 * (`assets/packs/`), including one board per variant pack. They are copied rather
 * than loaded because `LevelPack.init` needs a
 * Context, which a preview does not have — and hard-coding them is also what keeps the
 * images reproducible from a clean checkout.
 *
 * Rendering goes through [PipesTheme] rather than the shared `DynamicTheme` the other apps
 * use: Pipes pins its own dark grey scheme in `MainActivity`, so anything else would ship a
 * listing that does not match the app.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-board", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Board() {
        // 8×8 level 1, five of its eight pipes routed — a board part way through a solve.
        val level = level(
            id = "8×8_001",
            rows = 8,
            cols = 8,
            cells = rectangle(8, 8),
            endpoints = listOf(
                EndpointPair(0, listOf(CellPos(0, 1), CellPos(7, 3))),
                EndpointPair(1, listOf(CellPos(0, 3), CellPos(2, 1))),
                EndpointPair(2, listOf(CellPos(0, 4), CellPos(6, 5))),
                EndpointPair(3, listOf(CellPos(1, 3), CellPos(2, 6))),
                EndpointPair(4, listOf(CellPos(2, 3), CellPos(4, 7))),
                EndpointPair(5, listOf(CellPos(2, 4), CellPos(4, 4))),
                EndpointPair(6, listOf(CellPos(3, 1), CellPos(6, 1))),
                EndpointPair(7, listOf(CellPos(3, 4), CellPos(5, 5))),
            ),
            optimalMoves = 8,
        )
        PipesTheme {
            GameBoardScreen(
                state = GameBoardUiState(
                    levelData = level,
                    levelIndex = 0,
                    maxLevelIndex = 29,
                    gameState = paths(
                        1 to listOf(CellPos(0, 3), CellPos(0, 2), CellPos(1, 2), CellPos(1, 1), CellPos(2, 1)),
                        3 to listOf(CellPos(1, 3), CellPos(1, 4), CellPos(1, 5), CellPos(1, 6), CellPos(2, 6)),
                        5 to listOf(CellPos(2, 4), CellPos(2, 5), CellPos(3, 5), CellPos(4, 5), CellPos(4, 4)),
                        6 to listOf(CellPos(3, 1), CellPos(4, 1), CellPos(5, 1), CellPos(6, 1)),
                        7 to listOf(CellPos(3, 4), CellPos(3, 3), CellPos(4, 3), CellPos(5, 3), CellPos(5, 4), CellPos(5, 5)),
                    ),
                    moves = 5,
                    canUndo = true,
                ),
                actions = PipesActions.Noop,
                onBack = {},
                onLevelChange = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-solved", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Solved() {
        // Blob level 1, solved in the optimal 5 moves — shows off a non-rectangular pack.
        val level = level(
            id = "Blob_001",
            rows = 6,
            cols = 7,
            cells = buildSet {
                for (c in 0..2) add(CellPos(0, c))
                for (c in 0..6) add(CellPos(1, c))
                for (r in 2..4) for (c in 0..5) add(CellPos(r, c))
                add(CellPos(5, 0))
                add(CellPos(5, 3))
            },
            endpoints = listOf(
                EndpointPair(0, listOf(CellPos(0, 2), CellPos(2, 4))),
                EndpointPair(1, listOf(CellPos(0, 1), CellPos(3, 0))),
                EndpointPair(2, listOf(CellPos(5, 0), CellPos(4, 2))),
                EndpointPair(3, listOf(CellPos(2, 2), CellPos(1, 4))),
                EndpointPair(4, listOf(CellPos(1, 6), CellPos(5, 3))),
            ),
            optimalMoves = 5,
        )
        PipesTheme {
            GameBoardScreen(
                state = GameBoardUiState(
                    levelData = level,
                    levelIndex = 0,
                    maxLevelIndex = 29,
                    gameState = paths(
                        0 to listOf(
                            CellPos(0, 2), CellPos(1, 2), CellPos(1, 1), CellPos(2, 1), CellPos(3, 1),
                            CellPos(3, 2), CellPos(3, 3), CellPos(3, 4), CellPos(2, 4),
                        ),
                        1 to listOf(CellPos(0, 1), CellPos(0, 0), CellPos(1, 0), CellPos(2, 0), CellPos(3, 0)),
                        2 to listOf(CellPos(5, 0), CellPos(4, 0), CellPos(4, 1), CellPos(4, 2)),
                        3 to listOf(CellPos(2, 2), CellPos(2, 3), CellPos(1, 3), CellPos(1, 4)),
                        4 to listOf(
                            CellPos(1, 6), CellPos(1, 5), CellPos(2, 5), CellPos(3, 5),
                            CellPos(4, 5), CellPos(4, 4), CellPos(4, 3), CellPos(5, 3),
                        ),
                    ),
                    isLevelWon = true,
                    isCompleted = true,
                    moves = 5,
                    bestScore = 5,
                    canUndo = true,
                ),
                actions = PipesActions.Noop,
                onBack = {},
                onLevelChange = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-packs", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Packs() {
        PipesTheme {
            PackListScreen(
                packs = listOf(
                    PackProgress("5×5", "rectangular", 30, 30),
                    PackProgress("6×6", "rectangular", 22, 30),
                    PackProgress("7×7", "rectangular", 14, 30),
                    PackProgress("8×8", "rectangular", 5, 30),
                    PackProgress("9×9", "rectangular", 0, 30),
                    PackProgress("Tower", "tower", 0, 30),
                    PackProgress("Hourglass", "hourglass", 0, 30),
                    PackProgress("Blob", "blob", 0, 30),
                ),
                onOpenPack = {},
                onOpenSettings = {},
                onOpenGameCenter = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-bridges", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Bridges() {
        // Bridges level 1: red runs the column, yellow the row, crossing at the (2,2) bridge.
        val level = level(
            id = "Bridges_001",
            rows = 5,
            cols = 5,
            cells = rectangle(5, 5),
            endpoints = listOf(
                EndpointPair(0, listOf(CellPos(0, 2), CellPos(4, 2))),
                EndpointPair(1, listOf(CellPos(2, 0), CellPos(2, 4))),
                EndpointPair(2, listOf(CellPos(0, 0), CellPos(0, 1))),
                EndpointPair(3, listOf(CellPos(0, 4), CellPos(0, 3))),
                EndpointPair(4, listOf(CellPos(4, 0), CellPos(4, 1))),
                EndpointPair(5, listOf(CellPos(4, 4), CellPos(4, 3))),
            ),
            optimalMoves = 6,
            bridges = setOf(CellPos(2, 2)),
        )
        PipesTheme {
            GameBoardScreen(
                state = GameBoardUiState(
                    levelData = level,
                    levelIndex = 0,
                    maxLevelIndex = 2,
                    gameState = paths(
                        1 to listOf(CellPos(2, 0), CellPos(2, 1), CellPos(2, 2), CellPos(2, 3), CellPos(2, 4)),
                        0 to listOf(CellPos(0, 2), CellPos(1, 2), CellPos(2, 2), CellPos(3, 2), CellPos(4, 2)),
                    ),
                    moves = 2,
                    canUndo = true,
                ),
                actions = PipesActions.Noop,
                onBack = {},
                onLevelChange = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-wrap", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview5Wrap() {
        // Wrap level 3: red crosses the board via the (2,0)<->(2,4) torus edge.
        val level = level(
            id = "Wrap_003",
            rows = 5,
            cols = 5,
            cells = rectangle(5, 5),
            endpoints = listOf(
                EndpointPair(0, listOf(CellPos(0, 1), CellPos(4, 4))),
                EndpointPair(1, listOf(CellPos(0, 2), CellPos(2, 2))),
                EndpointPair(2, listOf(CellPos(0, 4), CellPos(1, 2))),
            ),
            optimalMoves = 3,
            extraEdges = listOf(CellPos(2, 0) to CellPos(2, 4)),
        )
        PipesTheme {
            GameBoardScreen(
                state = GameBoardUiState(
                    levelData = level,
                    levelIndex = 2,
                    maxLevelIndex = 2,
                    gameState = paths(
                        0 to listOf(
                            CellPos(0, 1), CellPos(1, 1), CellPos(2, 1), CellPos(2, 0),
                            CellPos(2, 4), CellPos(3, 4), CellPos(4, 4),
                        ),
                    ),
                    moves = 1,
                    canUndo = true,
                ),
                actions = PipesActions.Noop,
                onBack = {},
                onLevelChange = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "6-portals", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview6Portals() {
        // Portals level 2: the (0,4)<->(4,0) pair transits as an adjacency step.
        val portalEnds = mapOf(CellPos(0, 4) to CellPos(4, 0), CellPos(4, 0) to CellPos(0, 4))
        val level = level(
            id = "Portals_002",
            rows = 5,
            cols = 5,
            cells = rectangle(5, 5),
            endpoints = listOf(
                EndpointPair(0, listOf(CellPos(0, 0), CellPos(3, 1))),
                EndpointPair(1, listOf(CellPos(1, 2), CellPos(4, 0))),
                EndpointPair(2, listOf(CellPos(2, 2), CellPos(4, 3))),
            ),
            optimalMoves = 3,
            portals = portalEnds,
        )
        PipesTheme {
            GameBoardScreen(
                state = GameBoardUiState(
                    levelData = level,
                    levelIndex = 1,
                    maxLevelIndex = 2,
                    gameState = paths(
                        1 to listOf(CellPos(1, 2), CellPos(1, 3), CellPos(0, 3), CellPos(0, 4), CellPos(4, 0)),
                    ),
                    moves = 1,
                    canUndo = true,
                ),
                actions = PipesActions.Noop,
                onBack = {},
                onLevelChange = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "7-hexes", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview7Hexes() {
        // Hexes level 1 on the odd-r grid: staggered centers, six-neighbour pipes.
        val level = hexLevel(
            id = "Hexes_001",
            rows = 3,
            cols = 3,
            endpoints = listOf(
                EndpointPair(0, listOf(CellPos(0, 0), CellPos(2, 0))),
                EndpointPair(1, listOf(CellPos(0, 2), CellPos(2, 1))),
            ),
            optimalMoves = 2,
        )
        PipesTheme {
            GameBoardScreen(
                state = GameBoardUiState(
                    levelData = level,
                    levelIndex = 0,
                    maxLevelIndex = 2,
                    gameState = paths(
                        0 to listOf(CellPos(0, 0), CellPos(1, 0), CellPos(2, 0)),
                    ),
                    moves = 1,
                    canUndo = true,
                ),
                actions = PipesActions.Noop,
                onBack = {},
                onLevelChange = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "8-triplets", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview8Triplets() {
        // Triplets level 1: red chains all three dots of the middle row in one flow.
        val level = level(
            id = "Triplets_001",
            rows = 5,
            cols = 5,
            cells = rectangle(5, 5),
            endpoints = listOf(
                EndpointPair(0, listOf(CellPos(2, 0), CellPos(2, 2), CellPos(2, 4))),
                EndpointPair(1, listOf(CellPos(0, 0), CellPos(0, 4))),
                EndpointPair(2, listOf(CellPos(0, 1), CellPos(0, 3))),
                EndpointPair(3, listOf(CellPos(4, 0), CellPos(4, 4))),
                EndpointPair(4, listOf(CellPos(4, 1), CellPos(4, 3))),
            ),
            optimalMoves = 5,
        )
        PipesTheme {
            GameBoardScreen(
                state = GameBoardUiState(
                    levelData = level,
                    levelIndex = 0,
                    maxLevelIndex = 2,
                    gameState = paths(
                        0 to listOf(CellPos(2, 0), CellPos(2, 1), CellPos(2, 2), CellPos(2, 3), CellPos(2, 4)),
                    ),
                    moves = 1,
                    canUndo = true,
                ),
                actions = PipesActions.Noop,
                onBack = {},
                onLevelChange = {},
            )
        }
    }

    /** Every cell of a full [rows]×[cols] grid, as the rectangular packs use. */
    private fun rectangle(rows: Int, cols: Int): Set<CellPos> = buildSet {
        for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c))
    }

    /**
     * A level with adjacency derived from [cells] the way the pack loader derives it, so the
     * sample data is a real level rather than a rendering-only shell. [extraEdges] are
     * bidirectional long-range links (wrap edges, portal pairs) unioned into the adjacency,
     * mirroring `levelFromJson`.
     */
    private fun level(
        id: String,
        rows: Int,
        cols: Int,
        cells: Set<CellPos>,
        endpoints: List<EndpointPair>,
        optimalMoves: Int,
        bridges: Set<CellPos> = emptySet(),
        portals: Map<CellPos, CellPos> = emptyMap(),
        extraEdges: List<Pair<CellPos, CellPos>> = emptyList(),
        renderPositions: Map<CellPos, RenderPos>? = null,
        grid: String = "square",
    ): LevelData {
        val dirs = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        val adjacency = cells.associateWith { cell ->
            dirs.mapNotNull { (dr, dc) ->
                CellPos(cell.row + dr, cell.col + dc).takeIf { it in cells }
            }
        }.mapValues { (_, neighbors) -> neighbors.toMutableSet() }.toMutableMap()
        for ((a, b) in portals) adjacency.getOrPut(a) { mutableSetOf() }.add(b)
        for ((a, b) in extraEdges) {
            adjacency.getOrPut(a) { mutableSetOf() }.add(b)
            adjacency.getOrPut(b) { mutableSetOf() }.add(a)
        }
        val merged = adjacency.mapValues { (_, neighbors) -> neighbors.toList() }
        return LevelData(
            id, rows, cols, cells, merged, renderPositions, endpoints, bridges, optimalMoves,
            portals, grid,
        )
    }

    /**
     * A level on the odd-r hex grid, with six-neighbour adjacency and staggered centers derived
     * the way the pack loader derives them for `"grid": "hex"`.
     */
    private fun hexLevel(
        id: String,
        rows: Int,
        cols: Int,
        endpoints: List<EndpointPair>,
        optimalMoves: Int,
    ): LevelData {
        val cells = rectangle(rows, cols)
        val adjacency = cells.associateWith { cell ->
            val cellDirs = if (cell.row % 2 == 0) {
                listOf(-1 to -1, -1 to 0, 0 to -1, 0 to 1, 1 to -1, 1 to 0)
            } else {
                listOf(-1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to 0, 1 to 1)
            }
            cellDirs.mapNotNull { (dr, dc) ->
                CellPos(cell.row + dr, cell.col + dc).takeIf { it in cells }
            }
        }
        val positions = cells.associateWith { cell ->
            RenderPos(cell.col + 0.5f * (cell.row and 1), cell.row * 0.8660254f)
        }
        return LevelData(
            id, rows, cols, cells, adjacency, positions, endpoints, emptySet(), optimalMoves,
            emptyMap(), "hex",
        )
    }

    /** Committed pipes. Only [PipesGameState.paths] is drawn; cell ownership is engine state. */
    private fun paths(vararg entries: Pair<Int, List<CellPos>>): PipesGameState =
        PipesGameState(paths = entries.toMap())
}
