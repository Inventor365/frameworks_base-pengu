/*
 * SPDX-FileCopyrightText: 2026 Lunaris-AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.clocks

import android.graphics.Paint
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.android.systemui.res.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Sizes the Tall Stack clock from the window it is shown in, so it keeps the same proportions
 * on every display size, density and orientation.
 */
object TallStackClock {
    private const val HOURS = "tall_stack_hours"
    private const val MINUTES = "tall_stack_minutes"
    private const val DATE = "tall_stack_date"
    private const val DIGITS = "0123456789"
    private const val DATE_SAMPLE = "00/00"

    /** Gap between the hour and minute glyphs, as a share of the digit height. */
    private const val ROW_GAP = 0.03f
    /** Height of the date glyphs, as a share of the digit height. */
    private const val DATE_HEIGHT = 0.085f
    /** Gap between the hours and the date, as a share of the digit height. */
    private const val DATE_GAP = 0.04f
    /** Most of the window width one row may take. */
    private const val MAX_ROW_WIDTH = 0.6f
    /** Room the date may grow into beyond the sample text. */
    private const val DATE_SLACK = 1.2f

    /** Fits the clock to the window it is shown in. */
    @JvmStatic
    fun fit(root: View) {
        val config = root.resources.configuration
        val density = root.resources.displayMetrics.density
        fit(root, config.screenWidthDp * density, config.screenHeightDp * density)
    }

    /** Fits the clock to a window of the given size in pixels, for previews drawn smaller. */
    @JvmStatic
    fun fit(root: View, windowWidth: Float, windowHeight: Float) {
        val hours = root.findViewWithTag<TextView>(HOURS) ?: return
        val minutes = root.findViewWithTag<TextView>(MINUTES) ?: return
        val res = root.resources
        val bounds = Rect()

        val paint = Paint(hours.paint)
        paint.textSize = 100f
        paint.getTextBounds(DIGITS, 0, DIGITS.length, bounds)
        val glyphPerSize = bounds.height() / 100f
        var size = res.getFraction(R.fraction.keyguard_clock_tall_stack_height, 1, 1) *
            windowHeight / glyphPerSize
        val date = root.findViewWithTag<TextView>(DATE)
        paint.textSize = size
        val rowWidth = paint.measureText("00") * (1f + DATE_GAP + DATE_HEIGHT * 2f)
        if (rowWidth > windowWidth * MAX_ROW_WIDTH) size *= windowWidth * MAX_ROW_WIDTH / rowWidth
        val digitHeight = size * glyphPerSize

        hours.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        minutes.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        paint.textSize = size
        paint.getTextBounds(DIGITS, 0, DIGITS.length, bounds)
        val metrics = paint.fontMetrics
        // With includeFontPadding off a line spans ascent to descent.
        val topInset = bounds.top - metrics.ascent
        val bottomInset = metrics.descent - bounds.bottom
        (minutes.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = (digitHeight * ROW_GAP - topInset - bottomInset).roundToInt()
            minutes.layoutParams = it
        }

        if (date == null) return
        val box = date.parent as? View ?: return
        val datePaint = Paint(date.paint)
        datePaint.textSize = 100f
        datePaint.getTextBounds(DIGITS, 0, DIGITS.length, bounds)
        val dateSize = digitHeight * DATE_HEIGHT * 100f / bounds.height()
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, dateSize)
        datePaint.textSize = dateSize
        val dateMetrics = datePaint.fontMetrics
        val lineHeight = ceil(dateMetrics.descent - dateMetrics.ascent).toInt()
        val lineWidth = ceil(datePaint.measureText(DATE_SAMPLE) * DATE_SLACK).toInt()
        // The date is turned a quarter, so its box swaps the line's width and height.
        date.layoutParams = date.layoutParams.also {
            it.width = lineWidth
            it.height = lineHeight
        }
        (box.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.width = lineHeight
            it.height = lineWidth
            it.topMargin = topInset.roundToInt()
            it.marginStart = (digitHeight * DATE_GAP).roundToInt()
            box.layoutParams = it
        }
    }
}
