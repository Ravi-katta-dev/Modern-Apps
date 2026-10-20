package com.vayunmathur.tvlauncher.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.library.ui.IconApps
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.tvlauncher.domain.TvRowOrder
import com.vayunmathur.tvlauncher.platform.TvTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Space under each tile reserved for the focus caption. */
val TvCaptionReserve = 28.dp

private val MoveFinishKeys = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)

/**
 * One 16:9 banner tile: focus scale + caption, glass fallback, move-mode outline.
 *
 * OK behaviour depends on [reorderArmed]: disarmed it presses through [onPress]; armed a
 * long-OK picks the tile up ([onPickUp]) and a later OK drops it ([onFinishMove]).
 */
@Composable
fun TvBannerTile(
    tile: TvTile,
    tileWidth: Dp,
    artHeight: Dp,
    moving: Boolean,
    reorderArmed: Boolean,
    focusRequester: FocusRequester?,
    onPress: () -> Unit,
    onEdit: () -> Unit,
    onPickUp: () -> Unit,
    onMove: (Int) -> Unit,
    onFinishMove: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Focus you cannot miss: bigger lift plus a white ring. A bare 1.1x scale on a dim
    // grid reads as nothing from a sofa.
    val scale = animatedFloat(if (focused || moving) 1.15f else 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(tileWidth)
            .height(artHeight + TvCaptionReserve),
    ) {
        Box(
            modifier = Modifier
                .width(tileWidth)
                .height(artHeight)
                .scale(scale)
                .clip(MaterialTheme.shapes.largeIncreased)
                .background(
                    if (tile.banner != null) Color(0xFF1E1A1C) else Color(0x13FFFFFF),
                )
                .then(
                    if (focused || moving) {
                        // Double ring from theme tokens: a dark outer outline guarantees contrast
                        // against white banner art, and the primary inner ring carries the theme
                        // accent. A single white ring vanishes on white tiles.
                        Modifier
                            .border(
                                width = 6.dp,
                                color = Color.Black,
                                shape = MaterialTheme.shapes.largeIncreased,
                            )
                            .padding(6.dp)
                            .border(
                                width = 3.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.largeIncreased,
                            )
                    } else if (tile.banner == null) {
                        Modifier.border(
                            width = 2.dp,
                            color = Color(0x17FFFFFF),
                            shape = MaterialTheme.shapes.largeIncreased,
                        )
                    } else {
                        Modifier
                    },
                )
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                // No separate focusable(): clickable() provides focus handling, and a
                // standalone focusable() before it splits focus-grab and press into two OK
                // pushes on a D-pad (first press focuses, second clicks).
                .clickable(onClick = onPress)
                .onPreviewKeyEvent { event ->
                    // Long-OK (held center) picks the tile up directly when reorder mode is
                    // armed; otherwise it opens the edit menu. One edge only: handling both
                    // edges double-fires.
                    if (reorderArmed && !moving) {
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val held = event.key == Key.DirectionCenter || event.key == Key.Enter ||
                            event.key == Key.NumPadEnter
                        if (held && event.nativeKeyEvent.isLongPress) {
                            onPickUp()
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
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
            TvBannerArt(tile = tile)
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
                    .width(tileWidth)
                    .height(TvCaptionReserve)
                    .padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TvBannerArt(tile: TvTile) {
    val banner = tile.banner
    if (banner != null) {
        val bitmap = rememberRaster(banner, 320, 180)
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(320.dp),
            )
        } else {
            TvGlassIcon(tile = tile)
        }
    } else {
        TvGlassIcon(tile = tile)
    }
}

/** Rasterises a drawable off-main; null while loading or on failure (caller shows fallback). */
@Composable
fun rememberRaster(drawable: Drawable, width: Int, height: Int): ImageBitmap? {
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
