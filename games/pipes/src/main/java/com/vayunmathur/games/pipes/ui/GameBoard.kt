package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.platform.PipesGameState

@Composable
fun GameBoard(
    levelData: LevelData,
    gameState: PipesGameState,
    activeColor: Int?,
    activePath: List<CellPos>,
    onStartDraw: (CellPos) -> Unit,
    onExtendPath: (CellPos) -> Unit,
    onCommitDraw: () -> Unit,
    isLevelWon: Boolean,
    modifier: Modifier = Modifier,
    colorblind: Boolean = false
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
    val boardSize = minOf(maxWidth, maxHeight)
    // For staggered hex layouts contentW/H come from renderPositions bounds, not rows/cols.
    val renderPositions = levelData.renderPositions
    val hasRenderPositions = renderPositions != null
    val (contentW, contentH) = if (hasRenderPositions && levelData.cells.isNotEmpty()) {
        val xs = renderPositions.filterKeys { it in levelData.cells }.values.map { it.x }
        val ys = renderPositions.filterKeys { it in levelData.cells }.values.map { it.y }
        if (xs.isEmpty()) 1f to 1f else (xs.maxOrNull()!! - xs.minOrNull()!! + 1f) to (ys.maxOrNull()!! - ys.minOrNull()!! + 1f)
    } else {
        levelData.cols.toFloat() to levelData.rows.toFloat()
    }
    // Hex rows overlap vertically (0.866 spacing) so shrink slightly to avoid clipping.
    val hexShrink = if (levelData.grid == "hex") 0.92f else 1f
    val effectiveMaxDim = maxOf(contentW, contentH, 1f) / hexShrink
    val maxDim = maxOf(maxOf(levelData.rows, levelData.cols).toFloat(), effectiveMaxDim)
    val cellSizeDp = boardSize / maxDim

    val cellSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { cellSizeDp.toPx() }

    // Center the board within the square canvas using the actual cells' bounding
    // box, so non-square / non-rectangular shapes aren't stuck in the top-left.
    // Skip when explicit render positions are provided.
    val (offsetCol, offsetRow) = if (hasRenderPositions || levelData.cells.isEmpty()) {
        0f to 0f
    } else {
        val minRow = levelData.cells.minOf { it.row }
        val maxRow = levelData.cells.maxOf { it.row }
        val minCol = levelData.cells.minOf { it.col }
        val maxCol = levelData.cells.maxOf { it.col }
        val usedCols = maxCol - minCol + 1
        val usedRows = maxRow - minRow + 1
        val ox = (maxDim - usedCols) / 2f - minCol
        val oy = (maxDim - usedRows) / 2f - minRow
        ox to oy
    }

    // Shift render-position layouts so min sits at 0 and center in the square canvas.
    val (shiftX, shiftY) = if (hasRenderPositions && levelData.cells.isNotEmpty()) {
        val xs = renderPositions.filterKeys { it in levelData.cells }.values.map { it.x }
        val ys = renderPositions.filterKeys { it in levelData.cells }.values.map { it.y }
        val minX = xs.minOrNull() ?: 0f
        val minY = ys.minOrNull() ?: 0f
        val offX = (maxDim - contentW) / 2f - minX
        val offY = (maxDim - contentH) / 2f - minY
        offX to offY
    } else {
        0f to 0f
    }

    val cellRects = levelData.cells.associateWith { cell ->
        val (x, y) = levelData.renderPositions?.get(cell)?.let { (it.x + shiftX) to (it.y + shiftY) }
            ?: ((cell.col + offsetCol) to (cell.row + offsetRow))
        Rect(Offset(x * cellSizePx, y * cellSizePx), Size(cellSizePx, cellSizePx))
    }

    val isHex = levelData.grid == "hex"

    fun hitTest(offset: Offset): CellPos? {
        if (!isHex) {
            return cellRects.entries.firstOrNull { (_, rect) ->
                rect.contains(offset)
            }?.key
        }
        // Hex: nearest center within the hex radius wins (rects overlap vertically).
        var best: CellPos? = null
        var bestDist = Float.MAX_VALUE
        for ((cell, rect) in cellRects) {
            val d = (rect.center - offset).getDistanceSquared()
            if (d < bestDist && d <= (cellSizePx * 0.62f) * (cellSizePx * 0.62f)) {
                bestDist = d
                best = cell
            }
        }
        if (best != null) return best
        return cellRects.entries.firstOrNull { (_, rect) -> rect.contains(offset) }?.key
    }

    val boardBounds = if (cellRects.isEmpty()) Rect.Zero else Rect(
        left = cellRects.values.minOf { it.left },
        top = cellRects.values.minOf { it.top },
        right = cellRects.values.maxOf { it.right },
        bottom = cellRects.values.maxOf { it.bottom }
    )

    // While dragging, a finger that leaves the board keeps driving the border
    // cell nearest to it, so the user doesn't have to come back inside to
    // continue the pipe.
    fun hitTestDrag(offset: Offset): CellPos? {
        hitTest(offset)?.let { return it }
        if (cellRects.isEmpty() || boardBounds.contains(offset)) return null
        return cellRects.entries.minWithOrNull(
            compareBy(
                { squaredDistanceToRect(it.value, offset) },
                { (it.value.center - offset).getDistanceSquared() }
            )
        )?.key
    }

    Canvas(
        modifier = Modifier
            .size(boardSize)
            .pointerInput(levelData, isLevelWon) {
                if (isLevelWon) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pos = event.changes.firstOrNull()?.position ?: continue

                        when {
                            event.changes.any { it.pressed && !it.previousPressed } -> {
                                val cell = hitTest(pos)
                                if (cell != null) onStartDraw(cell)
                            }
                            event.changes.any { it.pressed } -> {
                                val cell = hitTestDrag(pos)
                                if (cell != null) onExtendPath(cell)
                            }
                            event.changes.any { !it.pressed && it.previousPressed } -> {
                                onCommitDraw()
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        for (cell in levelData.cells) {
            val rect = cellRects[cell] ?: continue
            if (isHex) {
                drawHexCell(rect.center, cellSizePx * 0.52f)
            } else {
                drawEmptyCell(rect)
            }
        }

        // Portal pads sit under any pipe.
        for (portalCell in levelData.portals.keys) {
            val rect = cellRects[portalCell] ?: continue
            drawPortalRing(rect)
        }

        val displayPaths = if (activeColor != null) gameState.paths + (activeColor to activePath) else gameState.paths

        val jumpExits = mutableListOf<Triple<CellPos, Direction, androidx.compose.ui.graphics.Color>>()

        for ((colorIndex, path) in displayPaths) {
            if (path.isEmpty()) continue
            val color = PIPE_COLORS[colorIndex % PIPE_COLORS.size]

            for (i in path.indices) {
                val cell = path[i]
                val rect = cellRects[cell] ?: continue
                if (isHex) {
                    val connections = buildSet {
                        if (i > 0) hexDirOrNull(cellRects, cell, path[i - 1])?.let { add(it) }
                        if (i < path.lastIndex) hexDirOrNull(cellRects, cell, path[i + 1])?.let { add(it) }
                    }
                    drawHexPipeSegment(rect.center, cellSizePx * 0.5f, connections, color)
                } else {
                    val connections = buildSet {
                        if (i > 0) stepOrJump(levelData, cell, path[i - 1], color, jumpExits)?.let { add(it) }
                        if (i < path.lastIndex) stepOrJump(levelData, cell, path[i + 1], color, jumpExits)?.let { add(it) }
                    }
                    drawPipeSegment(rect, connections, color)
                }
            }
        }

        // Wrap jumps: dot at the board edge the pipe exits through. Portal jumps get no
        // marker — the ring above already explains the teleport.
        for ((cell, side, color) in jumpExits) {
            if (cell in levelData.portals) continue
            val rect = cellRects[cell] ?: continue
            drawWrapMarker(rect, side, color)
        }

        // Bridges: the second crosser humps over the first owner's pipe.
        for (bridge in levelData.bridges) {
            val rect = cellRects[bridge] ?: continue
            val owner = gameState.cellOwner[bridge]
            val over = displayPaths.entries.firstOrNull { (c, path) ->
                bridge in path && (owner == null || c != owner)
            }?.key
            if (over != null) {
                drawBridgeHump(rect, PIPE_COLORS[over % PIPE_COLORS.size])
            }
        }

        for (ep in levelData.endpoints) {
            val color = PIPE_COLORS[ep.colorIndex % PIPE_COLORS.size]
            for (cell in ep.cells) {
                val rect = cellRects[cell] ?: continue
                drawEndpointBall(rect, color)
                if (colorblind) drawColorLabel(rect, ep.colorIndex)
            }
        }
    }
    }
}

private fun squaredDistanceToRect(rect: Rect, point: Offset): Float {
    val dx = maxOf(rect.left - point.x, 0f, point.x - rect.right)
    val dy = maxOf(rect.top - point.y, 0f, point.y - rect.bottom)
    return dx * dx + dy * dy
}

private fun directionBetween(from: CellPos, to: CellPos): Direction? {
    return when {
        to.row < from.row -> Direction.UP
        to.row > from.row -> Direction.DOWN
        to.col < from.col -> Direction.LEFT
        to.col > from.col -> Direction.RIGHT
        else -> null
    }
}

/**
 * Adjacent step → its side. Long-range edge (wrap/portal) → side facing the board edge, plus a
 * marker queued by the caller. The pipe segment itself draws as if it exits that side.
 */
private fun stepOrJump(
    levelData: LevelData,
    cell: CellPos,
    neighbor: CellPos,
    color: androidx.compose.ui.graphics.Color,
    jumpExits: MutableList<Triple<CellPos, Direction, androidx.compose.ui.graphics.Color>>,
): Direction? {
    directionBetween(cell, neighbor)?.let {
        if (kotlin.math.abs(neighbor.row - cell.row) + kotlin.math.abs(neighbor.col - cell.col) == 1) return it
    }
    // Long-range: pick the side toward the nearest board edge from the cell's draw position.
    val pos = levelData.renderPositions?.get(cell) ?: com.vayunmathur.games.pipes.data.RenderPos(cell.col.toFloat(), cell.row.toFloat())
    val edgeDistances = listOf(
        Direction.LEFT to pos.x,
        Direction.RIGHT to (levelData.cols - 1 - pos.x),
        Direction.UP to pos.y,
        Direction.DOWN to (levelData.rows - 1 - pos.y)
    )
    val side = edgeDistances.minByOrNull { it.second }?.first ?: return null
    jumpExits.add(Triple(cell, side, color))
    return side
}

private fun hexDirOrNull(
    cellRects: Map<CellPos, Rect>,
    cell: CellPos,
    neighbor: CellPos,
): HexDirection? {
    val a = cellRects[cell]?.center ?: return null
    val b = cellRects[neighbor]?.center ?: return null
    if (a == b) return null
    return hexDirectionBetween(a.x, a.y, b.x, b.y)
}
