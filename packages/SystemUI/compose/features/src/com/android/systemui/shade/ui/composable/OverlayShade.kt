/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade.ui.composable

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.overscroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.LowestZIndexContentPicker
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.modifiers.thenIf
import com.android.mechanics.behavior.VerticalExpandContainerSpec
import com.android.mechanics.behavior.verticalExpandContainerBackground
import com.android.mechanics.compose.modifier.motionDriver
import com.android.systemui.penguin.CosFeature
import com.android.systemui.penguin.CosShadeSwitch
import com.android.systemui.penguin.rememberCosFeature
import com.android.systemui.qs.shared.style.glassBlurRegion
import com.android.systemui.qs.ui.composable.TileRevealFlag
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.ui.composable.LocalSceneContainerPreloadedResources
import com.android.systemui.shade.ui.ShadeColors.shadePanel
import com.android.systemui.shade.ui.ShadeColors.shadePanelScrimBehind
import com.android.systemui.shade.ui.composable.OverlayShade.Colors
import com.android.systemui.shade.ui.composable.OverlayShade.Dimensions
import com.android.systemui.shade.ui.composable.OverlayShade.rememberShadeExpansionMotion
import kotlin.math.min
import kotlinx.coroutines.flow.onCompletion

/** Renders a lightweight shade UI container, as an overlay. */
@Composable
fun ContentScope.OverlayShade(
    panelElement: ElementKey,
    alignmentOnWideScreens: Alignment.Horizontal,
    statusBarHeightPx: Int,
    enableTransparency: Boolean,
    onScrimClicked: () -> Unit,
    modifier: Modifier = Modifier,
    onBackgroundPlaced: (bounds: Rect, topCornerRadius: Float, bottomCornerRadius: Float) -> Unit =
        { _, _, _ ->
        },
    header: @Composable () -> Unit,
    wide: Boolean = false,
    cosPanel: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isFullWidth = LocalSceneContainerPreloadedResources.current.isFullWidthShade
    val spanWide = (wide || isLandscapeSplitShade()) && !isFullWidth
    val panelSpec = rememberShadeExpansionMotion(isFullWidth)
    val panelCornerRadiusPx = with(LocalDensity.current) { panelSpec.radius.toPx() }
    val panelAlignment =
        when {
            isFullWidth || spanWide -> Alignment.TopCenter
            alignmentOnWideScreens == Alignment.End -> Alignment.TopEnd
            else -> Alignment.TopStart
        }

    val switchMotion = remember { CosSwitchMotion() }
    LaunchedEffect(switchMotion) { switchMotion.drive() }
    val layoutDirection = LocalLayoutDirection.current
    var width by remember { mutableIntStateOf(0) }
    var windowTop by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(layoutState, contentKey, layoutDirection) {
        val side =
            (if (contentKey == Overlays.NotificationsShade) -1f else 1f) *
                (if (layoutDirection == LayoutDirection.Rtl) -1f else 1f)
        snapshotFlow {
                val transition =
                    layoutState.currentTransition as? TransitionState.Transition.ReplaceOverlay
                if (transition == null || transition.key != CosShadeSwitch) {
                    null
                } else {
                    val shown =
                        if (contentKey == transition.fromContent) transition.progress
                        else 1f - transition.progress
                    Pair(side * width * shown, transition.isUserInputOngoing)
                }
            }
            .onCompletion {
                switchMotion.active = false
                switchMotion.offset = 0f
                switchMotion.dragging = false
            }
            .collect { state ->
                switchMotion.active = state != null
                switchMotion.offset = state?.first ?: 0f
                switchMotion.dragging = state?.second ?: false
            }
    }
    Box(
        modifier
            .onGloballyPositioned {
                width = it.size.width
                windowTop = it.positionInWindow().y
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            event.changes.firstOrNull()?.let {
                                switchMotion.fingerY = windowTop + it.position.y
                            }
                        }
                    }
                }
            }
    ) {
      CompositionLocalProvider(LocalCosSwitchMotion provides switchMotion) {
        OverlayScrim(
            // The COS backdrop behind both shades is the same glass, so neither dims it further;
            // otherwise a Quick Settings style without the COS panel darkens its shade alone.
            showBackgroundColor =
                enableTransparency && !cosPanel && !rememberCosFeature(CosFeature.Shade),
            onClicked = onScrimClicked,
        )

        Box(
            modifier =
                Modifier.fillMaxSize()
                    .panelContainerPadding(
                        isFullWidth,
                        if (spanWide) Alignment.CenterHorizontally else alignmentOnWideScreens,
                        statusBarHeightPx,
                    ),
            contentAlignment = panelAlignment,
        ) {
            Panel(
                enableTransparency = enableTransparency,
                cosPanel = cosPanel,
                spec = panelSpec,
                modifier =
                    Modifier.overscroll(verticalOverscrollEffect)
                        .element(panelElement)
                        .thenIf(TileRevealFlag.isEnabled) {
                            Modifier.motionDriver(
                                contentScope = this@OverlayShade,
                                label = "OverlayShade",
                            )
                        }
                        .then(
                            if (spanWide) Modifier.fillMaxWidth()
                            else Modifier.width(Dimensions.PanelWidth)
                        )
                        .thenIf(isFullWidth) {
                            Modifier.fillMaxHeight().clickable(
                                interactionSource = null,
                                indication = null,
                                onClick = onScrimClicked,
                            )
                        }
                        // TODO(440566878): Investigate if this can be optimized by replacing with
                        // onLayoutRectChanged.
                        .onPlaced { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            val isTopRounded = panelSpec.isFloating
                            val bottomCornerRadius: Float =
                                if (isTopRounded) {
                                    min(panelCornerRadiusPx, bounds.height / 2)
                                } else {
                                    min(panelCornerRadiusPx, bounds.height)
                                }
                            val topCornerRadius = if (isTopRounded) bottomCornerRadius else 0f
                            onBackgroundPlaced(bounds, topCornerRadius, bottomCornerRadius)
                        },
                header = header.takeIf { isFullWidth },
                content = {
                    Box(
                        Modifier
                            // Prevent this element from resizing during the container reveal
                            // animation. The parent clips the content, so remeasuring the
                            // children (especially text) on every frame is unnecessary and
                            // can cause performance issues (jank).
                            // Not for the COS panels: their lookahead width places the content
                            // about half a panel outside the panel while they open, close or
                            // switch, so it looks stuck to the screen's centre or edge.
                            .thenIf(!cosPanel) { Modifier.approachLayout(
                                isMeasurementApproachInProgress = { layoutState.isTransitioning() }
                            ) { measurable, constraints ->
                                if (layoutState.currentTransition == null) {
                                    return@approachLayout measurable.measure(constraints).run {
                                        layout(width, height) { place(0, 0) }
                                    }
                                }

                                // Make sure that this layout node has the same size than when we
                                // are at rest.
                                val widthAtRest = lookaheadSize.width
                                val fixedWidthConstraints =
                                    constraints.copy(minWidth = widthAtRest, maxWidth = widthAtRest)
                                measurable.measure(fixedWidthConstraints).run {
                                    layout(width, height) { place(IntOffset.Zero) }
                                }
                            } }
                    ) {
                        content()
                    }
                },
            )
        }

        if (!isFullWidth) {
            header()
        }
      }
    }
}

@Composable
private fun ContentScope.Panel(
    enableTransparency: Boolean,
    cosPanel: Boolean = false,
    spec: VerticalExpandContainerSpec,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .disableSwipesWhenScrolling()
                .verticalExpandContainerBackground(
                    if (cosPanel) Color.Transparent
                    else Colors.panelBackground(enableTransparency),
                    spec,
                )
    ) {
        Column {
            header?.invoke()
            content()
        }
    }
}

@Composable
fun isLandscapeSplitShade(): Boolean {
    val configuration = LocalConfiguration.current
    return !LocalSceneContainerPreloadedResources.current.isFullWidthShade &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.smallestScreenWidthDp < 600
}

@Composable
@ReadOnlyComposable
@SuppressLint("ConfigurationScreenWidthHeight")
private fun getHalfScreenWidth() = LocalConfiguration.current.screenWidthDp.dp / 2

@Composable
private fun Modifier.panelContainerPadding(
    isFullWidthPanel: Boolean,
    alignment: Alignment.Horizontal,
    statusBarHeightPx: Int,
): Modifier {
    if (isFullWidthPanel) {
        return this
    }
    // On wide screens, the shade panel width is limited to half the screen width.
    val halfScreenWidth = getHalfScreenWidth()
    val (startPadding, endPadding) =
        when (alignment) {
            Alignment.Start -> Dimensions.PanelPaddingHorizontal to halfScreenWidth
            Alignment.End -> halfScreenWidth to Dimensions.PanelPaddingHorizontal
            else -> Dimensions.PanelPaddingHorizontal to Dimensions.PanelPaddingHorizontal
        }
    val paddings = PaddingValues(start = startPadding, end = endPadding)
    val layoutDirection = LocalLayoutDirection.current
    return windowInsetsPadding(
        WindowInsets.safeDrawing
            .union(WindowInsets(top = statusBarHeightPx))
            .union(
                WindowInsets(
                    left = paddings.calculateLeftPadding(layoutDirection),
                    right = paddings.calculateRightPadding(layoutDirection),
                )
            )
    )
}

object OverlayShade {
    object Elements {
        val Scrim = ElementKey("OverlayShadeScrim", contentPicker = LowestZIndexContentPicker)
    }

    object Colors {
        val ScrimBackground: Color
            @Composable
            @ReadOnlyComposable
            get() = Color(shadePanelScrimBehind(LocalContext.current))

        @Composable
        @ReadOnlyComposable
        fun panelBackground(transparencyEnabled: Boolean): Color {
            return Color(
                shadePanel(
                    context = LocalContext.current,
                    blurSupported = transparencyEnabled,
                    withScrim = false,
                )
            )
        }
    }

    object Dimensions {
        val PanelCornerRadius: Dp
            @Composable
            @ReadOnlyComposable
            get() = dimensionResource(R.dimen.overlay_shade_panel_shape_radius)

        val PanelPaddingHorizontal: Dp
            @Composable
            @ReadOnlyComposable
            get() = dimensionResource(R.dimen.shade_panel_margin_horizontal)

        val PanelWidth: Dp
            @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.shade_panel_width)
    }

    @Composable
    fun rememberShadeExpansionMotion(isFullWidth: Boolean): VerticalExpandContainerSpec {
        val radius = if (isFullWidth) 0.dp else Dimensions.PanelCornerRadius
        return remember(radius, isFullWidth) {
            VerticalExpandContainerSpec(isFloating = !isFullWidth, radius = radius)
        }
    }
}
