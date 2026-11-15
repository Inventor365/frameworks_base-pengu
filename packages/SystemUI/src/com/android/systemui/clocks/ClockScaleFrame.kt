/*
 * SPDX-FileCopyrightText: 2026 Project PenguinOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.clocks

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import kotlin.math.ceil
import kotlin.math.max

/**
 * Holds the custom lock screen clock. The clock is laid out at its natural height and drawn
 * scaled from its top edge, so this frame measures it without a height limit and takes the
 * height of the clock as drawn. A scaled clock is then never squeezed, and a clock whose content
 * changes size (a date or weather row, a fallback font, another style) resizes the frame in the
 * normal layout pass.
 */
class ClockScaleFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RelativeLayout(context, attrs) {

    /** Scale the clock is drawn at, from its top edge. */
    var drawScale: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val content = measuredHeight - paddingTop - paddingBottom
        val drawn = paddingTop + paddingBottom + ceil(content * drawScale).toInt()
        setMeasuredDimension(
            measuredWidthAndState,
            resolveSize(max(drawn, suggestedMinimumHeight), heightMeasureSpec),
        )
    }
}
