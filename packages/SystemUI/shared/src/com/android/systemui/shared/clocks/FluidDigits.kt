/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shared.clocks

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

object FluidDigits {
    private const val DIGIT_EM = 0.46f
    private const val GAP_EM = 0.1f
    private const val COLON_EM = 0.24f
    private const val STROKE_EM = 0.085f

    @JvmStatic fun heightEm(stretch: Float): Float = 0.742f + 0.003456f * stretch

    @JvmStatic
    fun stretchFor(height: Float, size: Float): Float = ((height / size - 0.742f) / 0.003456f)

    @JvmStatic
    fun width(text: CharSequence, size: Float): Float {
        var x = 0f
        text.forEachIndexed { i, c ->
            if (i > 0) x += GAP_EM * size
            x += (if (c == ':') COLON_EM else DIGIT_EM) * size
        }
        return x
    }

    @JvmStatic
    fun draw(
        canvas: Canvas,
        text: CharSequence,
        left: Float,
        top: Float,
        size: Float,
        stretch: Float,
        paint: Paint,
    ) {
        val height = size * heightEm(stretch)
        val stroke = STROKE_EM * size
        val style = paint.style
        val width = paint.strokeWidth
        val cap = paint.strokeCap
        val join = paint.strokeJoin
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        var x = left
        text.forEachIndexed { i, c ->
            if (i > 0) x += GAP_EM * size
            if (c == ':') {
                paint.style = Paint.Style.FILL
                val cx = x + COLON_EM * size / 2f
                val dot = stroke * 0.8f
                canvas.drawCircle(cx, top + height * 0.36f, dot, paint)
                canvas.drawCircle(cx, top + height * 0.68f, dot, paint)
                x += COLON_EM * size
            } else {
                paint.style = Paint.Style.STROKE
                digit(c)?.let { shape ->
                    val box = RectF(x, top, x + DIGIT_EM * size, top + height)
                    box.inset(stroke / 2f, stroke / 2f)
                    canvas.drawPath(shape(box), paint)
                }
                x += DIGIT_EM * size
            }
        }
        paint.style = style
        paint.strokeWidth = width
        paint.strokeCap = cap
        paint.strokeJoin = join
    }

    private fun digit(c: Char): ((RectF) -> Path)? =
        when (c) {
            '0' -> ::zero
            '1' -> ::one
            '2' -> ::two
            '3' -> ::three
            '4' -> ::four
            '5' -> ::five
            '6' -> ::six
            '7' -> ::seven
            '8' -> ::eight
            '9' -> ::nine
            else -> null
        }

    private fun RectF.r() = min(width() / 2f, height() / 4f)

    private fun zero(b: RectF) = Path().apply { addRoundRect(b, b.width() / 2f, min(b.width() / 2f, b.height() / 2f), Path.Direction.CW) }

    private fun one(b: RectF) =
        Path().apply {
            val x = b.centerX() + b.width() * 0.12f
            moveTo(b.left + b.width() * 0.15f, b.top + b.r() * 0.9f)
            lineTo(x, b.top)
            lineTo(x, b.bottom)
        }

    private fun two(b: RectF) =
        Path().apply {
            val r = b.r()
            moveTo(b.left, b.top + r)
            arcTo(RectF(b.left, b.top, b.right, b.top + 2 * r), 180f, 180f)
            val bend = b.top + r + max(0f, (b.height() - 2 * r) * 0.18f)
            lineTo(b.right, bend)
            cubicTo(b.right, bend + b.height() * 0.2f, b.left, b.bottom - b.height() * 0.3f, b.left, b.bottom)
            lineTo(b.right, b.bottom)
        }

    private fun three(b: RectF) =
        Path().apply {
            val r = b.r()
            val mid = b.centerY()
            moveTo(b.left, b.top + r)
            arcTo(RectF(b.left, b.top, b.right, b.top + 2 * r), 180f, 180f)
            lineTo(b.right, max(b.top + r, mid - r))
            arcTo(RectF(b.left, mid - 2 * r, b.right, mid), 0f, 90f)
            moveTo(b.centerX(), mid)
            arcTo(RectF(b.left, mid, b.right, mid + 2 * r), 270f, 90f)
            lineTo(b.right, min(b.bottom - r, max(mid + r, b.bottom - r)))
            arcTo(RectF(b.left, b.bottom - 2 * r, b.right, b.bottom), 0f, 180f)
        }

    private fun four(b: RectF) =
        Path().apply {
            val bar = b.top + b.height() * 0.68f
            val stem = b.left + b.width() * 0.78f
            moveTo(stem, b.top)
            lineTo(b.left, bar)
            lineTo(b.right, bar)
            moveTo(stem, b.top + b.height() * 0.3f)
            lineTo(stem, b.bottom)
        }

    private fun five(b: RectF) =
        Path().apply {
            val r = b.r()
            val bowl = max(b.top + b.height() * 0.38f, b.bottom - b.height() * 0.6f)
            moveTo(b.right, b.top)
            lineTo(b.left, b.top)
            lineTo(b.left, bowl + r * 0.4f)
            arcTo(RectF(b.left, bowl, b.right, bowl + 2 * r), 180f, 180f)
            lineTo(b.right, max(bowl + r, b.bottom - r))
            arcTo(RectF(b.left, b.bottom - 2 * r, b.right, b.bottom), 0f, 180f)
        }

    private fun six(b: RectF) =
        Path().apply {
            val r = b.r()
            val loop = max(b.top + 2 * r, b.bottom - max(2 * r, b.height() * 0.55f))
            addRoundRect(RectF(b.left, loop, b.right, b.bottom), r, r, Path.Direction.CW)
            moveTo(b.left, loop + r)
            lineTo(b.left, b.top + r)
            arcTo(RectF(b.left, b.top, b.right, b.top + 2 * r), 180f, 140f)
        }

    private fun seven(b: RectF) =
        Path().apply {
            moveTo(b.left, b.top)
            lineTo(b.right, b.top)
            cubicTo(
                b.right,
                b.top + b.height() * 0.25f,
                b.centerX() - b.width() * 0.05f,
                b.top + b.height() * 0.5f,
                b.centerX() - b.width() * 0.05f,
                b.bottom,
            )
        }

    private fun eight(b: RectF) =
        Path().apply {
            val mid = b.top + b.height() * 0.47f
            val narrow = b.width() * 0.06f
            val top = RectF(b.left + narrow, b.top, b.right - narrow, mid)
            val bottom = RectF(b.left, mid, b.right, b.bottom)
            addRoundRect(top, top.width() / 2f, min(top.width(), top.height()) / 2f, Path.Direction.CW)
            addRoundRect(bottom, bottom.width() / 2f, min(bottom.width(), bottom.height()) / 2f, Path.Direction.CW)
        }

    private fun nine(b: RectF) =
        Path().apply {
            val r = b.r()
            val loop = min(b.bottom - 2 * r, b.top + max(2 * r, b.height() * 0.55f))
            addRoundRect(RectF(b.left, b.top, b.right, loop), r, r, Path.Direction.CW)
            moveTo(b.right, loop - r)
            lineTo(b.right, b.bottom - r)
            arcTo(RectF(b.left, b.bottom - 2 * r, b.right, b.bottom), 0f, 140f)
        }
}
