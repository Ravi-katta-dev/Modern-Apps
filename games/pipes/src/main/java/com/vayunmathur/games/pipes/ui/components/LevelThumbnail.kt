package com.vayunmathur.games.pipes.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.library.ui.MaterialTheme

@Composable
fun LevelThumbnail(levelData: LevelData, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (levelData.cells.isEmpty()) return@Canvas
        val positions = levelData.renderPositions
        if (positions != null) {
            // Irregular layout (hex stagger, portal boards): normalize bounds to the canvas.
            val xs = levelData.cells.mapNotNull { positions[it]?.x }
            val ys = levelData.cells.mapNotNull { positions[it]?.y }
            if (xs.isEmpty()) return@Canvas
            val minX = xs.minOrNull()!!
            val minY = ys.minOrNull()!!
            val spanX = (xs.maxOrNull()!! - minX + 1f).coerceAtLeast(1f)
            val spanY = (ys.maxOrNull()!! - minY + 1f).coerceAtLeast(1f)
            val cell = minOf(size.width / spanX, size.height / spanY)
            val offX = (size.width - spanX * cell) / 2f - minX * cell
            val offY = (size.height - spanY * cell) / 2f - minY * cell
            for (c in levelData.cells) {
                val p = positions[c] ?: continue
                drawRect(color = color, topLeft = Offset(offX + p.x * cell, offY + p.y * cell), size = Size(cell * 0.85f, cell * 0.85f))
            }
            return@Canvas
        }
        val maxDim = maxOf(levelData.rows, levelData.cols)
        if (maxDim == 0) return@Canvas
        val cell = size.minDimension / maxDim
        val minRow = levelData.cells.minOf { it.row }
        val minCol = levelData.cells.minOf { it.col }
        val usedRows = levelData.cells.maxOf { it.row } - minRow + 1
        val usedCols = levelData.cells.maxOf { it.col } - minCol + 1
        val offX = (size.width - usedCols * cell) / 2f - minCol * cell
        val offY = (size.height - usedRows * cell) / 2f - minRow * cell
        for (c in levelData.cells) { drawRect(color = color, topLeft = Offset(offX + c.col * cell, offY + c.row * cell), size = Size(cell * 0.85f, cell * 0.85f)) }
    }
}
