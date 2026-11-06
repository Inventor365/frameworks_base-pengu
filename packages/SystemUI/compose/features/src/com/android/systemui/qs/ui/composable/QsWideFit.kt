/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.ui.composable

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Heights of the landscape Quick Settings column, so a styled panel can size itself to fit the
 * space that is really left instead of guessing it from the screen height.
 */
class QsWideFit {
    /** The visible height of the scrolling column. */
    var viewportPx by mutableIntStateOf(0)

    /** The full height of everything in the column. */
    var contentPx by mutableIntStateOf(0)

    /** The part of [contentPx] that the panel body itself takes. */
    var bodyPx by mutableIntStateOf(0)

    /** The height left for the body once everything else in the column is laid out. */
    val availableBodyPx: Int?
        get() =
            if (viewportPx <= 0 || contentPx <= 0 || bodyPx <= 0) null
            else viewportPx - (contentPx - bodyPx)
}

val LocalQsWideFit = staticCompositionLocalOf<QsWideFit?> { null }
