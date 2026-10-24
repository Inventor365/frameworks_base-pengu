/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade.ui.composable

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneTransitionLayoutState
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.systemui.penguin.CosFeature
import com.android.systemui.penguin.rememberCosFeature
import com.android.systemui.qs.shared.style.LocalGlassBlurAlpha
import com.android.systemui.qs.shared.style.glassBlurRegion
import com.android.systemui.scene.shared.model.Overlays

private val Shades = setOf<OverlayKey>(Overlays.NotificationsShade, Overlays.QuickSettingsShade)

@Composable
fun CosShadeGlass(state: SceneTransitionLayoutState) {
    if (!rememberCosFeature(CosFeature.Shade)) return
    val dark = isSystemInDarkTheme()
    val visibility = remember(state) { { shadeVisibility(state) } }
    CompositionLocalProvider(LocalGlassBlurAlpha provides visibility) {
        Spacer(
            Modifier.fillMaxSize()
                .glassBlurRegion(RectangleShape, material = if (dark) 5 else 6)
        )
    }
}

private fun shadeVisibility(state: SceneTransitionLayoutState): Float {
    val transition = state.currentTransition
    if (
        transition is TransitionState.Transition.ReplaceOverlay &&
            transition.fromContent in Shades &&
            transition.toContent in Shades
    ) {
        return 1f
    }
    val shown = state.transitionState.currentOverlays.any { it in Shades }
    return when {
        transition?.toContent in Shades -> transition!!.progress
        transition?.fromContent in Shades -> 1f - transition!!.progress
        shown -> 1f
        else -> 0f
    }.coerceIn(0f, 1f)
}
