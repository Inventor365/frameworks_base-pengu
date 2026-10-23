/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.penguin

import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface LockscreenClockFace {
    val isEnabled: Boolean

    val cosUnlock: Boolean

    @Composable fun Placeholder(large: Boolean, modifier: Modifier)

    @Composable fun Overlay(alpha: () -> Float, unlocking: () -> Float)

    fun handoff(): Handoff?

    fun onHandedOff()

    data class Handoff(val bounds: Rect, val fontHeight: Int)
}
