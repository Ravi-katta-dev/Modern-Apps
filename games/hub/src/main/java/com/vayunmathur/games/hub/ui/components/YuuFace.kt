package com.vayunmathur.games.hub.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig

/**
 * Deterministic vector renderer for a [YuuFaceConfig]: every part is drawn from the
 * config with size-relative math, so the same config renders identically at any size.
 * Pure — no ViewModel, no resources — so previews and the plaza grid can use it freely.
 *
 * Paint order: body -> ears -> face shape -> hair back -> eyes -> brows -> nose ->
 * mouth -> blush/wrinkles/mole -> glasses -> facial hair -> hair front.
 *
 * When [withBody] is true (editor preview), a simple Mii-like torso is drawn under
 * the head whose height/width follow bodyHeight/bodyWeight. Plaza badges and profile
 * headers keep the head-only form.
 */
@Composable
fun YuuFace(
    config: YuuFaceConfig,
    modifier: Modifier = Modifier,
    withBody: Boolean = false,
) {
    val spec = remember(config, withBody) { YuuDrawSpec(config, withBody) }
    Canvas(modifier = modifier) {
        spec.draw(this)
    }
}

private fun Long.toColor(): Color = Color(this)

/** Precomputed, denormalized draw parameters for one config. */
private class YuuDrawSpec(config: YuuFaceConfig, val withBody: Boolean = false) {
    val faceShape = config.faceShape.mod(YuuFaceConfig.FACE_SHAPES)
    val skin = config.skinTone.toColor()
    val skinShade = config.skinTone.toColor().copy(alpha = 1f).darken(0.12f)
    val earR = 0.06f + config.earSize * 0.07f
    val bodyH = 0.28f + config.bodyHeight * 0.30f
    val bodyW = 0.30f + config.bodyWeight * 0.35f
    val hairStyle = config.hairStyle.mod(YuuFaceConfig.HAIR_STYLES)
    val hair = config.hairColor.toColor()
    val eyeStyle = config.eyeStyle.mod(YuuFaceConfig.EYE_STYLES)
    val eyeColor = config.eyeColor.toColor()
    val eyeR = 0.045f + config.eyeSize * 0.05f
    val eyeDx = 0.10f + config.eyeSpacing * 0.12f
    val eyeDy = -0.02f + config.eyeHeight * 0.10f - 0.05f
    val eyeTilt = (config.eyeRotation - 0.5f) * 0.6f
    val browStyle = config.browStyle.mod(YuuFaceConfig.BROW_STYLES)
    val browColor = config.browColor.toColor()
    val browDy = -0.16f - config.browHeight * 0.08f
    val browTilt = (config.browRotation - 0.5f) * 0.9f
    val noseStyle = config.noseStyle.mod(YuuFaceConfig.NOSE_STYLES)
    val noseDy = (config.noseHeight - 0.5f) * 0.12f
    val mouthStyle = config.mouthStyle.mod(YuuFaceConfig.MOUTH_STYLES)
    val mouthColor = config.mouthColor.toColor()
    val mouthW = 0.10f + config.mouthSize * 0.14f
    val mouthDy = (config.mouthHeight - 0.5f) * 0.12f
    val glasses = config.glassesStyle.mod(YuuFaceConfig.GLASSES_STYLES + 1)
    val beard = config.beardStyle.mod(YuuFaceConfig.BEARD_STYLES + 1)
    val mole = config.moleStyle.mod(YuuFaceConfig.MOLE_STYLES + 1)
    val wrinkles = config.wrinkleStyle.mod(YuuFaceConfig.WRINKLE_STYLES + 1)
    val blush = config.blushStyle.mod(YuuFaceConfig.BLUSH_STYLES + 1)

    fun draw(scope: DrawScope) = with(scope) {
        // With a body the head sits higher so the torso fits below.
        val s = if (withBody) size.minDimension * 0.72f else size.minDimension
        val c = if (withBody) center + Offset(0f, -size.minDimension * 0.14f) else center
        if (withBody) drawBody(c, size.minDimension)
        drawEars(c, s)
        drawFaceShape(c, s)
        drawHairBack(c, s)
        drawEyes(c, s)
        drawBrows(c, s)
        drawNose(c, s)
        drawMouth(c, s)
        drawSkinDetails(c, s)
        drawGlasses(c, s)
        drawBeard(c, s)
        drawHairFront(c, s)
    }

    private fun DrawScope.drawBody(headC: Offset, full: Float) {
        val neckW = bodyW * full * 0.28f
        val neckTop = headC.y + faceHalfHeight() * full * 0.72f - full * 0.02f
        // Neck.
        drawOval(
            skin,
            topLeft = Offset(headC.x - neckW / 2, neckTop - full * 0.06f),
            size = Size(neckW, full * 0.12f),
        )
        // Torso: rounded shoulders widening with weight, lengthening with height.
        val torsoTop = neckTop + full * 0.04f
        val torsoH = bodyH * full
        val torsoW = bodyW * full
        val p = Path().apply {
            moveTo(headC.x - torsoW * 0.45f, torsoTop + torsoH * 0.18f)
            quadraticTo(headC.x - torsoW * 0.55f, torsoTop, headC.x - torsoW * 0.30f, torsoTop)
            lineTo(headC.x + torsoW * 0.30f, torsoTop)
            quadraticTo(headC.x + torsoW * 0.55f, torsoTop, headC.x + torsoW * 0.45f, torsoTop + torsoH * 0.18f)
            lineTo(headC.x + torsoW * 0.50f, torsoTop + torsoH)
            lineTo(headC.x - torsoW * 0.50f, torsoTop + torsoH)
            close()
        }
        drawPath(p, skinShade)
    }

    private fun DrawScope.drawEars(c: Offset, s: Float) {
        val faceW = faceHalfWidth() * s
        val ey = c.y - s * 0.02f
        drawCircle(skin, radius = earR * s, center = Offset(c.x - faceW, ey))
        drawCircle(skin, radius = earR * s, center = Offset(c.x + faceW, ey))
    }

    private fun faceHalfWidth(): Float = when (faceShape) {
        1 -> 0.30f
        2 -> 0.27f
        3 -> 0.32f
        4 -> 0.26f
        5 -> 0.34f
        6 -> 0.29f
        7 -> 0.31f
        else -> 0.30f
    }

    private fun faceHalfHeight(): Float = when (faceShape) {
        1 -> 0.36f
        2 -> 0.40f
        3 -> 0.34f
        4 -> 0.42f
        5 -> 0.35f
        6 -> 0.38f
        7 -> 0.33f
        else -> 0.37f
    }

    private fun DrawScope.drawFaceShape(c: Offset, s: Float) {
        val w = faceHalfWidth() * s
        val h = faceHalfHeight() * s
        val cy = c.y - s * 0.02f
        when (faceShape) {
            // 1: square jaw — rounded rect.
            1 -> drawRoundRectPath(c, w, h, 0.18f * s)
            // 2: heart — wider top via two overlapping ovals.
            2 -> {
                drawOvalPath(c + Offset(0f, -h * 0.18f), w, h * 0.85f)
                drawOvalPath(c + Offset(0f, h * 0.28f), w * 0.72f, h * 0.62f)
            }
            // 4: long oval.
            4 -> drawOvalPath(c.copy(y = cy), w * 0.92f, h)
            // 6: round cheeks — main oval plus cheek circles.
            6 -> {
                drawOvalPath(c.copy(y = cy), w, h)
                drawCircle(skin, w * 0.42f, c + Offset(-w * 0.62f, h * 0.35f))
                drawCircle(skin, w * 0.42f, c + Offset(w * 0.62f, h * 0.35f))
            }
            // 7: wide — flattened oval.
            7 -> drawOvalPath(c.copy(y = cy), w, h * 0.88f)
            // 0, 3, 5: ovals with varying width (covered by faceHalfWidth).
            else -> drawOvalPath(c.copy(y = cy), w, h)
        }
    }

    private fun DrawScope.drawOvalPath(c: Offset, hw: Float, hh: Float) {
        drawOval(skin, topLeft = Offset(c.x - hw, c.y - hh), size = Size(hw * 2, hh * 2))
    }

    private fun DrawScope.drawRoundRectPath(c: Offset, hw: Float, hh: Float, r: Float) {
        drawRoundRect(
            skin,
            topLeft = Offset(c.x - hw, c.y - hh),
            size = Size(hw * 2, hh * 2),
            cornerRadius = CornerRadius(r, r),
        )
    }

    private fun DrawScope.drawHairBack(c: Offset, s: Float) {
        if (hairStyle == 0) return
        val w = faceHalfWidth() * s
        val topY = c.y - faceHalfHeight() * s - s * 0.02f
        when (hairStyle % 4) {
            // Long back hair: side panels behind the face.
            1 -> {
                drawOval(hair, Offset(c.x - w * 0.95f, topY + s * 0.30f), Size(w * 0.45f, s * 0.55f))
                drawOval(hair, Offset(c.x + w * 0.50f, topY + s * 0.30f), Size(w * 0.45f, s * 0.55f))
            }
            // Tied / bun sides.
            2 -> {
                drawCircle(hair, s * 0.09f, Offset(c.x - w - s * 0.05f, topY + s * 0.10f))
                drawCircle(hair, s * 0.09f, Offset(c.x + w + s * 0.05f, topY + s * 0.10f))
            }
            // Afro halo behind everything.
            3 -> drawCircle(hair, w * 1.25f, Offset(c.x, topY + s * 0.08f))
        }
    }

    private fun eyeCenters(c: Offset, s: Float): Pair<Offset, Offset> {
        val y = c.y + eyeDy * s
        return Offset(c.x - eyeDx * s, y) to Offset(c.x + eyeDx * s, y)
    }

    private fun DrawScope.drawEyes(c: Offset, s: Float) {
        val (l, r) = eyeCenters(c, s)
        val whiteRx = eyeR * s * 1.25f
        val whiteRy = eyeR * s * (if (eyeStyle % 3 == 2) 1.35f else 1.0f)
        for (ec in listOf(l, r)) {
            when (eyeStyle % 4) {
                // 0: classic white + iris.
                0, 1 -> {
                    drawOval(Color.White, Offset(ec.x - whiteRx, ec.y - whiteRy), Size(whiteRx * 2, whiteRy * 2))
                    drawCircle(eyeColor, eyeR * s * 0.62f, ec)
                    drawCircle(Color.Black, eyeR * s * 0.30f, ec)
                    drawCircle(Color.White, eyeR * s * 0.12f, ec + Offset(-eyeR * s * 0.15f, -eyeR * s * 0.15f))
                }
                // 2: tall oval eyes.
                2 -> {
                    drawOval(Color.White, Offset(ec.x - whiteRx, ec.y - whiteRy), Size(whiteRx * 2, whiteRy * 2))
                    drawOval(eyeColor, Offset(ec.x - whiteRx * 0.5f, ec.y - whiteRy * 0.5f), Size(whiteRx, whiteRy))
                    drawCircle(Color.Black, eyeR * s * 0.28f, ec)
                }
                // 3: simple dot eyes (Mii-like minimal).
                else -> drawCircle(eyeColor, eyeR * s * 0.55f, ec)
            }
            // Upper lash line, tilted by eyeRotation.
            val tilt = eyeTilt * s * 0.1f
            drawLine(
                Color.Black,
                Offset(ec.x - whiteRx, ec.y - whiteRy * 0.55f + tilt),
                Offset(ec.x + whiteRx, ec.y - whiteRy * 0.55f - tilt),
                strokeWidth = s * 0.012f,
            )
        }
    }

    private fun DrawScope.drawBrows(c: Offset, s: Float) {
        val (l, r) = eyeCenters(c, s)
        val y = c.y + browDy * s
        for (ec in listOf(l, r)) {
            val w2 = eyeR * s * 1.2f
            val yOff = (ec.x - c.x) * eyeTilt * 0.3f
            // Brow rotation: tilt each brow around its own center.
            val rot = browTilt * s * 0.35f
            when (browStyle % 4) {
                0 -> drawLine(browColor, Offset(ec.x - w2, y + yOff + rot), Offset(ec.x + w2, y + yOff - rot), s * 0.03f)
                1 -> {
                    val p = Path().apply {
                        moveTo(ec.x - w2, y + s * 0.02f + yOff + rot)
                        quadraticTo(ec.x, y - s * 0.045f + yOff, ec.x + w2, y + yOff - rot)
                    }
                    drawPath(p, browColor, style = Stroke(s * 0.028f))
                }
                2 -> drawLine(browColor, Offset(ec.x - w2, y + s * 0.02f + yOff + rot), Offset(ec.x + w2, y - s * 0.02f + yOff - rot), s * 0.035f)
                else -> {
                    drawLine(browColor, Offset(ec.x - w2, y + yOff + rot), Offset(ec.x + w2 * 0.4f, y - s * 0.03f + yOff), s * 0.03f)
                    drawLine(browColor, Offset(ec.x + w2 * 0.4f, y - s * 0.03f + yOff), Offset(ec.x + w2, y + yOff - rot), s * 0.03f)
                }
            }
        }
    }

    private fun DrawScope.drawNose(c: Offset, s: Float) {
        val ny = c.y + s * 0.06f + noseDy * s
        val ink = skinShade
        when (noseStyle % 4) {
            0 -> drawLine(ink, Offset(c.x + s * 0.01f, ny - s * 0.03f), Offset(c.x + s * 0.01f, ny + s * 0.03f), s * 0.014f)
            1 -> {
                val p = Path().apply {
                    moveTo(c.x - s * 0.02f, ny - s * 0.03f)
                    quadraticTo(c.x + s * 0.03f, ny, c.x - s * 0.005f, ny + s * 0.03f)
                }
                drawPath(p, ink, style = Stroke(s * 0.014f))
            }
            2 -> drawCircle(ink, s * 0.018f, Offset(c.x, ny + s * 0.01f))
            else -> drawLine(ink, Offset(c.x - s * 0.03f, ny + s * 0.02f), Offset(c.x + s * 0.03f, ny + s * 0.02f), s * 0.016f)
        }
    }

    private fun DrawScope.drawMouth(c: Offset, s: Float) {
        val my = c.y + s * 0.16f + mouthDy * s
        val w2 = mouthW * s
        when (mouthStyle % 6) {
            0 -> drawLine(mouthColor, Offset(c.x - w2 * 0.8f, my), Offset(c.x + w2 * 0.8f, my), s * 0.018f)
            1 -> {
                val p = Path().apply {
                    moveTo(c.x - w2, my - s * 0.01f)
                    quadraticTo(c.x, my + s * 0.09f, c.x + w2, my - s * 0.01f)
                }
                drawPath(p, mouthColor, style = Stroke(s * 0.018f))
            }
            2 -> drawOval(mouthColor, Offset(c.x - w2 * 0.55f, my - s * 0.035f), Size(w2 * 1.1f, s * 0.09f))
            3 -> {
                drawOval(Color.White, Offset(c.x - w2 * 0.7f, my - s * 0.02f), Size(w2 * 1.4f, s * 0.10f))
                drawLine(Color.Black, Offset(c.x - w2 * 0.7f, my + s * 0.015f), Offset(c.x + w2 * 0.7f, my + s * 0.015f), s * 0.010f)
            }
            4 -> {
                val p = Path().apply {
                    moveTo(c.x - w2, my + s * 0.02f)
                    quadraticTo(c.x, my - s * 0.05f, c.x + w2, my + s * 0.02f)
                }
                drawPath(p, mouthColor, style = Stroke(s * 0.016f))
            }
            else -> {
                val p = Path().apply {
                    moveTo(c.x - w2, my)
                    lineTo(c.x - w2 * 0.3f, my + s * 0.02f)
                    lineTo(c.x + w2 * 0.3f, my + s * 0.02f)
                    lineTo(c.x + w2, my)
                    lineTo(c.x + w2 * 0.5f, my + s * 0.055f)
                    lineTo(c.x - w2 * 0.5f, my + s * 0.055f)
                    close()
                }
                drawPath(p, Color.White)
                drawPath(p, mouthColor, style = Stroke(s * 0.012f))
            }
        }
    }

    private fun DrawScope.drawSkinDetails(c: Offset, s: Float) {
        // Blush.
        if (blush != 0) {
            val bc = mouthColor.copy(alpha = 0.45f)
            val (l, r) = eyeCenters(c, s)
            val by = c.y + s * 0.12f
            val bw = s * (0.05f + blush * 0.012f)
            drawCircle(bc, bw, Offset(l.x - s * 0.05f, by))
            drawCircle(bc, bw, Offset(r.x + s * 0.05f, by))
            if (blush == 3) {
                drawCircle(bc, bw * 0.7f, Offset(l.x - s * 0.05f, by - bw))
                drawCircle(bc, bw * 0.7f, Offset(r.x + s * 0.05f, by - bw))
            }
        }
        // Wrinkles: forehead lines + crow's feet.
        if (wrinkles != 0) {
            val ink = skinShade.copy(alpha = 0.8f)
            val fy = c.y - faceHalfHeight() * s * 0.55f
            repeat(wrinkles) { i ->
                val yy = fy + i * s * 0.035f
                val wob = s * 0.10f * (1f - i * 0.15f)
                drawLine(ink, Offset(c.x - wob, yy), Offset(c.x + wob, yy), s * 0.008f)
            }
            val (l, r) = eyeCenters(c, s)
            drawLine(ink, Offset(l.x - eyeR * s * 1.7f, l.y), Offset(l.x - eyeR * s * 1.2f, l.y - s * 0.02f), s * 0.008f)
            drawLine(ink, Offset(r.x + eyeR * s * 1.7f, r.y), Offset(r.x + eyeR * s * 1.2f, r.y - s * 0.02f), s * 0.008f)
        }
        // Mole / beauty mark.
        if (mole != 0) {
            val mp = when (mole % 4) {
                1 -> Offset(c.x - s * 0.12f, c.y + s * 0.10f)
                2 -> Offset(c.x + s * 0.12f, c.y + s * 0.10f)
                3 -> Offset(c.x + s * 0.05f, c.y + s * 0.22f)
                else -> Offset(c.x - s * 0.05f, c.y + s * 0.02f)
            }
            drawCircle(Color(0xFF4A2B2B), s * 0.014f, mp)
        }
    }

    private fun DrawScope.drawGlasses(c: Offset, s: Float) {
        if (glasses == 0) return
        val (l, r) = eyeCenters(c, s)
        val frame = Color(0xFF2B2B2B)
        val rw = eyeR * s * 1.7f
        val rh = eyeR * s * 1.4f
        when (glasses % 3) {
            // 1: round frames + bridge.
            1 -> {
                for (ec in listOf(l, r)) drawCircle(frame, rw, ec, style = Stroke(s * 0.018f))
                drawLine(frame, Offset(l.x + rw, l.y - rh * 0.3f), Offset(r.x - rw, r.y - rh * 0.3f), s * 0.016f)
            }
            // 2: square frames.
            2 -> {
                for (ec in listOf(l, r)) {
                    drawRoundRectPath(ec, rw, rh, s * 0.02f, frame, s * 0.018f)
                }
                drawLine(frame, Offset(l.x + rw, l.y), Offset(r.x - rw, r.y), s * 0.016f)
            }
            // 0 (mod 3): shades.
            else -> {
                for (ec in listOf(l, r)) drawOval(frame, Offset(ec.x - rw, ec.y - rh), Size(rw * 2, rh * 2))
                drawRoundRect(frame, Offset(c.x - rw * 0.4f, l.y - rh * 0.4f), Size(rw * 0.8f, rh * 0.5f))
            }
        }
    }

    private fun DrawScope.drawRoundRectPath(c: Offset, hw: Float, hh: Float, r: Float, color: Color, width: Float) {
        drawRoundRect(
            color,
            topLeft = Offset(c.x - hw, c.y - hh),
            size = Size(hw * 2, hh * 2),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width),
        )
    }

    private fun DrawScope.drawBeard(c: Offset, s: Float) {
        if (beard == 0) return
        val w = faceHalfWidth() * s
        val my = c.y + s * 0.16f
        when (beard % 3) {
            // 1: moustache above the mouth.
            1 -> {
                drawOval(hair, Offset(c.x - mouthW * s * 1.1f, my - s * 0.045f), Size(mouthW * s * 1.1f, s * 0.035f))
                drawOval(hair, Offset(c.x - s * 0.01f, my - s * 0.045f), Size(mouthW * s * 1.1f, s * 0.035f))
            }
            // 2: goatee below the mouth.
            2 -> drawOval(hair, Offset(c.x - w * 0.28f, my + s * 0.03f), Size(w * 0.56f, s * 0.16f))
            // 0 (mod 3): full beard framing the jaw.
            else -> {
                val p = Path().apply {
                    moveTo(c.x - w * 0.95f, my - s * 0.06f)
                    quadraticTo(c.x - w * 0.7f, my + s * 0.30f, c.x, my + s * 0.32f)
                    quadraticTo(c.x + w * 0.7f, my + s * 0.30f, c.x + w * 0.95f, my - s * 0.06f)
                    quadraticTo(c.x + w * 0.5f, my + s * 0.10f, c.x, my + s * 0.10f)
                    quadraticTo(c.x - w * 0.5f, my + s * 0.10f, c.x - w * 0.95f, my - s * 0.06f)
                    close()
                }
                drawPath(p, hair)
            }
        }
    }

    private fun DrawScope.drawHairFront(c: Offset, s: Float) {
        if (hairStyle == 0) return
        val w = faceHalfWidth() * s
        val topY = c.y - faceHalfHeight() * s - s * 0.02f
        when (hairStyle % 6) {
            // 1: side part sweep.
            1 -> {
                val p = Path().apply {
                    moveTo(c.x - w * 1.05f, topY + s * 0.12f)
                    quadraticTo(c.x - w * 0.4f, topY - s * 0.10f, c.x + w * 1.05f, topY + s * 0.06f)
                    quadraticTo(c.x + w * 0.6f, topY - s * 0.02f, c.x + w * 0.2f, topY + s * 0.10f)
                    quadraticTo(c.x - w * 0.5f, topY + s * 0.02f, c.x - w * 1.05f, topY + s * 0.12f)
                    close()
                }
                drawPath(p, hair)
            }
            // 2: straight fringe.
            2 -> drawOval(hair, Offset(c.x - w * 1.02f, topY - s * 0.06f), Size(w * 2.04f, s * 0.20f))
            // 3: afro cap.
            3 -> {
                drawCircle(hair, w * 1.25f, Offset(c.x, topY + s * 0.08f))
                drawCircle(skin, w * 0.98f, Offset(c.x, topY + s * 0.22f))
            }
            // 4: spiky top.
            4 -> {
                val p = Path().apply {
                    val n = 7
                    moveTo(c.x - w * 1.05f, topY + s * 0.10f)
                    repeat(n) { i ->
                        val x0 = c.x - w * 1.05f + i * (w * 2.1f / n)
                        val x1 = x0 + w * 2.1f / n / 2
                        val x2 = x0 + w * 2.1f / n
                        lineTo(x1, topY - s * (0.08f + (i % 2) * 0.05f))
                        lineTo(x2, topY + s * 0.10f)
                    }
                    quadraticTo(c.x, topY + s * 0.16f, c.x - w * 1.05f, topY + s * 0.10f)
                    close()
                }
                drawPath(p, hair)
            }
            // 5: bald with side wisps.
            5 -> {
                drawOval(hair, Offset(c.x - w * 1.02f, topY + s * 0.02f), Size(w * 0.28f, s * 0.16f))
                drawOval(hair, Offset(c.x + w * 0.74f, topY + s * 0.02f), Size(w * 0.28f, s * 0.16f))
            }
            // 0 (mod 6): neat cap.
            else -> {
                val p = Path().apply {
                    moveTo(c.x - w * 1.02f, topY + s * 0.14f)
                    quadraticTo(c.x, topY - s * 0.14f, c.x + w * 1.02f, topY + s * 0.14f)
                    quadraticTo(c.x + w * 0.9f, topY + s * 0.02f, c.x + w * 0.55f, topY + s * 0.10f)
                    quadraticTo(c.x, topY - s * 0.02f, c.x - w * 0.55f, topY + s * 0.10f)
                    quadraticTo(c.x - w * 0.9f, topY + s * 0.02f, c.x - w * 1.02f, topY + s * 0.14f)
                    close()
                }
                drawPath(p, hair)
            }
        }
    }

    private fun Color.darken(fraction: Float): Color {
        // Simple RGB scaling via cosine-free linear darkening; alpha preserved.
        fun Float.d() = (this * (1f - fraction)).coerceIn(0f, 1f)
        return copy(red = red.d(), green = green.d(), blue = blue.d())
    }
}
