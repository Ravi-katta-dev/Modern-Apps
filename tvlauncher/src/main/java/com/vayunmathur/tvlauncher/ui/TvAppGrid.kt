package com.vayunmathur.tvlauncher.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.library.ui.IconApps
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.tvlauncher.domain.TvRowOrder
import com.vayunmathur.tvlauncher.platform.TvHomeState
import com.vayunmathur.tvlauncher.platform.TvTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app banner grid: six 16:9 tiles per row like the tv-detox reference.
 *
 * Focused tiles scale to ~1.1 with their label underneath; unfocused banner tiles show art
 * only. Tiles without banner art fall back to a glass tile with the app icon + label, so the
 * grid never reflows while art loads (the LauncherAppIcon placeholder precedent).
 *
 * Art rasterises off-main via [rememberRaster]: `Drawable.toBitmap` on a low-end TV box in
 * composition is jank, and on a cold grid is an ANR.
 */
@Composable
fun TvAppGrid(
    state: TvHomeState,
    onOpenTile: (String) -> Unit,
    onOpenEditMenu: (String) -> Unit,
    onMoveKey: (String, Int) -> Unit,
    onFinishMove: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(TvRowOrder.PER_ROW),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp),
        modifier = Modifier.fillMaxWidth(),
        // A grid inside the screen's scroller: the whole page scrolls as one.
        userScrollEnabled = false,
    ) {
        items(state.rowApps, key = { it.packageName }) { tile ->
            TvBannerTile(
                tile = tile,
                moving = state.movingKey == tile.packageName,
                focusRequester = if (tile == state.rowApps.firstOrNull()) firstFocus else null,
                onOpen = { onOpenTile(tile.packageName) },
                onEdit = { onOpenEditMenu(tile.packageName) },
                onMove = { step -> onMoveKey(tile.packageName, step) },
                onFinishMove = onFinishMove,
            )
        }
    }
    // Cold start: D-pad does nothing until something holds focus, so hand it to the first
    // tile once the row arrives.
    LaunchedEffect(state.rowApps.isNotEmpty(), state.loading) {
        if (state.rowApps.isNotEmpty() && !state.loading) {
            runCatching { firstFocus.requestFocus() }
        }
    }
}

private val MoveFinishKeys = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)

@Composable
private fun TvBannerTile(
    tile: TvTile,
    moving: Boolean,
    focusRequester: FocusRequester?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onFinishMove: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = animatedFloat(if (focused) 1.1f else 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .scale(scale)
                .clip(MaterialTheme.shapes.largeIncreased)
                .background(
                    if (tile.banner != null) Color(0xFF1E1A1C) else Color(0x13FFFFFF),
                )
                .then(
                    if (tile.banner == null) {
                        Modifier.border(
                            width = 2.dp,
                            color = Color(0x17FFFFFF),
                            shape = MaterialTheme.shapes.largeIncreased,
                        )
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (moving) {
                        Modifier.border(
                            width = 4.dp,
                            color = Color.White,
                            shape = MaterialTheme.shapes.largeIncreased,
                        )
                    } else {
                        Modifier
                    },
                )
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .clickable(onClick = onOpen)
                .onPreviewKeyEvent { event ->
                    // One edge only: D-pad repeats arrive as KeyDown, Menu as KeyUp. Handling
                    // both edges double-fires (finish-then-edit-menu); handling neither edge
                    // consistently lets the KeyUp fall through to clickable and launch the app.
                    if (moving) {
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                onMove(-1)
                                true
                            }
                            Key.DirectionRight -> {
                                onMove(1)
                                true
                            }
                            Key.DirectionUp -> {
                                onMove(-TvRowOrder.PER_ROW)
                                true
                            }
                            Key.DirectionDown -> {
                                onMove(TvRowOrder.PER_ROW)
                                true
                            }
                            in MoveFinishKeys -> {
                                onFinishMove()
                                true
                            }
                            // Back cancels the move; the screen's BackHandler owns it, so let
                            // it through rather than swallowing it here.
                            Key.Back -> false
                            // Swallow the rest while moving so stray keys cannot open menus.
                            Key.Menu -> true
                            else -> false
                        }
                    } else {
                        if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Menu -> {
                                onEdit()
                                true
                            }
                            else -> false
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val banner = tile.banner
            if (banner != null) {
                val bitmap = rememberRaster(banner, 320, 180)
                if (bitmap != null) {
                    Image(
                        painter = BitmapPainter(bitmap),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TvGlassIcon(tile = tile)
                }
            } else {
                TvGlassIcon(tile = tile)
            }
        }
        // Banner tiles show their name underneath when focused (the tv-detox caption).
        if (focused || tile.banner == null) {
            Text(
                text = tile.label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
    }
}

/** Rasterises a drawable off-main; null while loading or on failure (caller shows fallback). */
@Composable
private fun rememberRaster(drawable: Drawable, width: Int, height: Int): ImageBitmap? {
    val density = LocalContext.current.resources.displayMetrics.density
    val w = (width * density).toInt().coerceAtLeast(1)
    val h = (height * density).toInt().coerceAtLeast(1)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, drawable, w, h) {
        value = withContext(Dispatchers.Default) {
            runCatching { drawable.toBitmap(width = w, height = h).asImageBitmap() }.getOrNull()
        }
    }
    return bitmap
}

/** Quiet glass tile for apps without TV banner art: icon above label. */
@Composable
private fun TvGlassIcon(tile: TvTile) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val icon: Drawable? = tile.appIcon
        if (icon != null) {
            val bitmap = rememberRaster(icon, 128, 128)
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                IconApps(modifier = Modifier.size(44.dp), tint = Color.White)
            }
        } else {
            IconApps(modifier = Modifier.size(44.dp), tint = Color.White)
        }
        Text(
            text = tile.label,
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xE0FFFFFF),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
        )
    }
}
