/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.shared.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

object GlassBackdrop {
    data class Region(val bounds: Rect, val corner: Float, val alpha: Float)

    val regions = mutableStateMapOf<Any, Region>()

    val drawing = mutableStateOf(false)

    fun update(key: Any, region: Region?) {
        if (region == null || region.alpha <= 0f) {
            regions.remove(key)
        } else if (regions[key] != region) {
            regions[key] = region
        }
    }
}

@Composable
fun Modifier.glassBackdropRegion(corner: Dp, alpha: Float = 1f): Modifier {
    val key = remember { Any() }
    val bounds = remember { mutableStateOf<Rect?>(null) }
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    DisposableEffect(key) { onDispose { GlassBackdrop.update(key, null) } }
    SideEffect {
        bounds.value?.let { GlassBackdrop.update(key, GlassBackdrop.Region(it, cornerPx, alpha)) }
    }
    return onGloballyPositioned {
        val rect = it.boundsInWindow()
        bounds.value = rect
        GlassBackdrop.update(key, GlassBackdrop.Region(rect, cornerPx, alpha))
    }
}
