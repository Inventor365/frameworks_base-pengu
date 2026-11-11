/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.composable.elements

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.android.compose.animation.Easings
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementContentScope
import com.android.compose.animation.scene.PropertyTransformationBuilder
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.clocks.ClockStyle
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenUpperRegionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenUpperRegionViewModel.Decision
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.KeyguardBlueprintLog
import com.android.systemui.notifications.ui.composable.LocalLockscreenStackAlignment
import com.android.systemui.notifications.ui.composable.onPullDown
import com.android.systemui.penguin.LockscreenPlayer
import com.android.systemui.penguin.LockscreenStack
import com.android.systemui.plugins.keyguard.ui.composable.elements.BaseLockscreenElement.ElementSource
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Clock
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.MediaCarousel
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Notifications
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Region
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Smartspace
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementProvider
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenMovableParentKeys.UpperRegion.NarrowLayout as NarrowScenes
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenMovableParentKeys.UpperRegion.WideLayout as WideScenes
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope.Companion.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope.Companion.NestedScenes
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.shared.model.ShadeMode
import com.google.errorprone.annotations.CompileTimeConstant
import java.util.Optional
import javax.inject.Inject
import kotlin.math.roundToInt

private val FOLD_PILL_OFFSET = 64.dp
private const val FOLD_PILL_SCALE = 0.6f
private val FOLD_BLUR = 24.dp

@SysUISingleton
/** Provides a combined element for all lockscreen ui above the lock icon. */
class LockscreenUpperRegionElementProvider
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    @KeyguardBlueprintLog private val blueprintLog: LogBuffer,
    private val viewModelFactory: LockscreenUpperRegionViewModel.Factory,
    private val keyguardClockViewModel: KeyguardClockViewModel,
    player: Optional<LockscreenPlayer>,
) : LockscreenElementProvider {
    private val player = player.orElse(null)
    private val logger = Logger(blueprintLog, "LockscreenUpperRegionElementProvider")
    override val elements: List<LockscreenElement> by lazy { listOf(UpperRegionElement()) }

    private inner class UpperRegionElement : LockscreenElement {
        override val key = Region.Upper
        override val context = this@LockscreenUpperRegionElementProvider.context
        override val source = ElementSource.STANDARD

        @Composable
        override fun LockscreenScope<ElementContentScope>.LockscreenElement() {
            val viewModel = rememberViewModel("LockscreenUpperRegion") { viewModelFactory.create() }
            val layoutType = logDecision("LayoutType") { getLayoutDecision(viewModel.shadeMode) }
            val layout =
                remember(viewModel, layoutType) {
                    when (layoutType) {
                        LayoutType.WIDE -> WideLayout(viewModel)
                        LayoutType.NARROW -> NarrowLayout(viewModel)
                    }
                }

            // The clock region (and the media player in it) scrolls up with the expanded
            // notification list, fading as it goes, instead of the list sliding over it.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = 1f - LockscreenStack.clockCover
                    translationY = -LockscreenStack.coverScroll
                }
            ) {
                with(layout) { Layout() }
            }
        }
    }

    abstract inner class RegionLayout(val viewModel: LockscreenUpperRegionViewModel) {
        @Composable abstract fun LockscreenScope<ContentScope>.Layout(modifier: Modifier = Modifier)

        @Composable
        protected fun LockscreenScope<ContentScope>.Notifications(
            aodAlignment: Alignment,
            modifier: Modifier = Modifier,
            stackTopPadding: Dp = 0.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize().then(modifier)) {
                AODNotifications(
                    Modifier.align(aodAlignment),
                    horizontalAlignment =
                        if (aodAlignment == Alignment.TopCenter) Alignment.CenterHorizontally
                        else Alignment.Start,
                )
                // Make the Notification section overlap with the AOD icons, to avoid jumps while
                // animating them in.
                val folded by remember {
                    derivedStateOf { (player?.nowBarFraction ?: 0f) >= 0.5f }
                }
                // Folding into the now bar takes the expanded stack with it.
                LaunchedEffect(folded) { if (folded) LockscreenStack.collapse() }
                val nowBar = player
                // The stack's own view takes the touches on its cards: it asks here whether a pull
                // down folds them into the now bar.
                DisposableEffect(nowBar) {
                    if (nowBar != null) {
                        LockscreenStack.canPullDown = {
                            !LockscreenStack.isExpanded() && nowBar.canFoldIntoNowBar
                        }
                        LockscreenStack.pullDown = { nowBar.foldIntoNowBar() }
                    }
                    onDispose {
                        LockscreenStack.canPullDown = { false }
                        LockscreenStack.pullDown = {}
                    }
                }
                // Expanded in place, the stack is the lock screen's full height panel instead.
                val expanded by LockscreenStack.expanded.collectAsStateWithLifecycle()
                if (!expanded) AnimatedVisibility(
                    viewModel.isNotificationStackActive && !folded,
                    modifier = Modifier.padding(top = stackTopPadding),
                ) {
                    LockscreenElement(
                        Notifications.Stack,
                        modifier =
                            Modifier.burnInAware(isClock = false)
                                // Lifted by a swipe up on its cards on its way to expanding
                                // in place (taken by the stack's view, which gets the touches).
                                .offset { IntOffset(0, LockscreenStack.lift.roundToInt()) }
                                // Like the media card, pulling the folded stack down folds it
                                // and the player into the now bar.
                                .onPullDown(
                                    enabled = {
                                        !LockscreenStack.isExpanded() &&
                                            nowBar?.canFoldIntoNowBar == true
                                    }
                                ) {
                                    nowBar?.foldIntoNowBar()
                                },
                    )
                }
            }
        }

        @Composable
        protected fun LockscreenScope<ContentScope>.MediaCarousel(modifier: Modifier = Modifier) {
            val bottomPadding =
                dimensionResource(R.dimen.notification_section_divider_height_lockscreen)
            val notificationWidth =
                dimensionResource(R.dimen.shade_panel_width) -
                    (dimensionResource(R.dimen.overlay_qs_layout_horizontal_padding) * 2)
            val widthModifier =
                if (viewModel.shadeMode == ShadeMode.Dual) {
                    Modifier.width(notificationWidth)
                } else {
                    Modifier.fillMaxWidth()
                }

            LockscreenElement(
                MediaCarousel,
                modifier =
                    Modifier.then(widthModifier)
                        .padding(bottom = bottomPadding)
                        .then(modifier)
                        .foldedIntoNowBar(),
            )
        }

        /**
         * Like ColorOS, folding into the now bar sends the card down to the pill: it travels to the
         * bottom of the screen, shrinks towards the pill's width and blurs out in the first half,
         * then gives up its height in one step. It stays composed: the now bar reads its state.
         */
        @Composable
        private fun Modifier.foldedIntoNowBar(): Modifier {
            val nowBar = player ?: return this
            val travel = remember { floatArrayOf(0f) }
            return onGloballyPositioned {
                    val root = it.findRootCoordinates().size.height
                    val center = it.positionInRoot().y + it.size.height / 2f
                    travel[0] = (root - center).coerceAtLeast(0f)
                }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0))
                    val height = if (nowBar.nowBarFraction >= 0.5f) 0 else placeable.height
                    layout(placeable.width, height) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    val out = (nowBar.nowBarFraction * 2f).coerceAtMost(1f)
                    // Stop short of the bottom edge, where the pill sits between the shortcuts.
                    translationY = out * (travel[0] - FOLD_PILL_OFFSET.toPx()).coerceAtLeast(0f)
                    scaleX = 1f - (1f - FOLD_PILL_SCALE) * out
                    scaleY = 1f - (1f - FOLD_PILL_SCALE) * out
                    alpha = 1f - out * out
                    val blur = out * FOLD_BLUR.toPx()
                    renderEffect =
                        if (blur > 0f) BlurEffect(blur, blur, TileMode.Decal) else null
                }
        }

        @Composable
        protected fun LockscreenScope<ContentScope>.AODNotifications(
            modifier: Modifier = Modifier,
            horizontalAlignment: Alignment.Horizontal = Alignment.Start,
        ) {
            Column(modifier, horizontalAlignment = horizontalAlignment) {
                LockscreenElement(Notifications.AOD.Promoted, Modifier.padding(bottom = 4.dp))
                LockscreenElement(Notifications.AOD.IconShelf)
            }
        }

        protected fun TransitionBuilder.configureClockCenteringTransition() {
            val duration =
                if (viewModel.shouldSkipTransition) 1 else CLOCK_CENTERING_DURATION_MILLIS
            spec = tween(duration, easing = Easings.Emphasized)
        }

        protected fun TransitionBuilder.configureClockSwitchTransition(
            enter: PropertyTransformationBuilder.() -> Unit,
            exit: PropertyTransformationBuilder.() -> Unit,
        ) {
            val duration = if (viewModel.shouldSkipTransition) 1 else 300
            spec = tween(duration, easing = Easings.Emphasized)

            // Since Smartspace cards are guaranteed to be shared between the small and large clock
            // regions, it's convenient to anchor the movement of the small clock elements to it.
            anchoredTranslate(Clock.Small, anchor = Smartspace.Cards)
            anchoredTranslate(Smartspace.DWA.SmallClock.Row, anchor = Smartspace.Cards)
            anchoredTranslate(Smartspace.DWA.SmallClock.Column, anchor = Smartspace.Cards)

            if (!viewModel.shouldSkipTransition) {
                timestampRange(endMillis = 133) { exit() }
                timestampRange(startMillis = 133, endMillis = 300) { enter() }
            }
        }

        /**
         * Show the AOD notifications only once the large clock has mostly moved up, and hide them before
         * it moves down.
         */
        protected fun TransitionBuilder.fadeAodNotificationsWithClockMove(entering: Boolean) {
            if (viewModel.shouldSkipTransition) return
            fractionRange(
                start = if (entering) 0.6f else 0f,
                end = if (entering) 1f else 0.25f,
            ) {
                fade(Notifications.AOD.Promoted)
                fade(Notifications.AOD.IconShelf)
            }
        }

        protected fun PropertyTransformationBuilder.fadeLargeClock() {
            fade(Clock.Large)
            fade(Smartspace.DWA.LargeClock.Above)
            fade(Smartspace.DWA.LargeClock.Below)
        }

        protected fun PropertyTransformationBuilder.fadeSmallClock() {
            fade(Clock.Small)
            fade(Smartspace.DWA.SmallClock.Row)
            fade(Smartspace.DWA.SmallClock.Column)
        }
    }

    /** The Narrow Layouts are intended for phones */
    inner class NarrowLayout(viewModel: LockscreenUpperRegionViewModel) : RegionLayout(viewModel) {
        @Composable
        override fun LockscreenScope<ContentScope>.Layout(modifier: Modifier) {
            val clockHidden = rememberClockHiddenState()
            val customStyle = rememberCustomClockStyle()

            val clockSize =
                logDecision("NarrowLayout: ClockSize") {
                    viewModel.evaluateClockSize {
                        when {
                            viewModel.isMediaVisible -> Decision(ClockSize.SMALL, "isMediaVisible")
                            else -> Decision(ClockSize.LARGE, "Default Case")
                        }
                    }
                }

            val narrowPadding =
                dimensionResource(R.dimen.notification_side_paddings_single) +
                    dimensionResource(R.dimen.notification_panel_margin_horizontal)

            NestedScenes(
                sceneKey =
                    when {
                        clockSize == ClockSize.SMALL -> NarrowScenes.SmallClock
                        viewModel.isNotificationStackActive ->
                            NarrowScenes.LargeClockWithNotifications
                        else -> NarrowScenes.LargeClock
                    },
                transitions = {
                    from(
                        from = NarrowScenes.LargeClock,
                        to = NarrowScenes.LargeClockWithNotifications,
                    ) {
                        configureClockCenteringTransition()
                        fadeAodNotificationsWithClockMove(entering = true)
                    }
                    from(
                        from = NarrowScenes.LargeClockWithNotifications,
                        to = NarrowScenes.LargeClock,
                    ) {
                        configureClockCenteringTransition()
                        fadeAodNotificationsWithClockMove(entering = false)
                    }
                    from(
                        from = NarrowScenes.LargeClockWithNotifications,
                        to = NarrowScenes.SmallClock,
                    ) {
                        configureClockSwitchTransition(
                            enter = { fadeSmallClock() },
                            exit = { fadeLargeClock() },
                        )
                    }
                    from(
                        from = NarrowScenes.SmallClock,
                        to = NarrowScenes.LargeClockWithNotifications,
                    ) {
                        configureClockSwitchTransition(
                            enter = { fadeLargeClock() },
                            exit = { fadeSmallClock() },
                        )
                    }
                    from(from = NarrowScenes.SmallClock, to = NarrowScenes.LargeClock) {
                        configureClockSwitchTransition(
                            enter = { fadeLargeClock() },
                            exit = { fadeSmallClock() },
                        )
                    }
                    from(from = NarrowScenes.LargeClock, to = NarrowScenes.SmallClock) {
                        configureClockSwitchTransition(
                            enter = { fadeSmallClock() },
                            exit = { fadeLargeClock() },
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = narrowPadding).then(modifier),
                debugName = "NarrowLayout - Clocks",
            ) {
                scene(NarrowScenes.LargeClock) {
                    when {
                        customStyle != 0 -> CustomClockView()
                        clockHidden -> { /* no clock */ }
                        else -> LockscreenElement(Region.Clock.Large)
                    }
                }
                scene(NarrowScenes.LargeClockWithNotifications) {
                    // Keep the large clock in the upper part and show the notification stack below it. Reserve what
                    // the clock element lays out, between the old share and LARGE_CLOCK_WITH_NOTIFICATIONS_MAX_SHARE.
                    val clockBounds by
                        keyguardClockViewModel.clockEventController.largeClockBounds
                            .collectAsStateWithLifecycle()
                    val clockHeight = with(LocalDensity.current) { clockBounds.height.toDp() }
                    val dateBelowClock by
                        keyguardClockViewModel.shouldDateWeatherBeBelowLargeClock
                            .collectAsStateWithLifecycle()
                    // The date and weather row is laid out after the clock bounds, so it needs its own room
                    val dateRowReserve = if (dateBelowClock) DATE_WEATHER_ROW_RESERVE else 0.dp
                    val clockReserve =
                        clockHeight +
                            dateRowReserve +
                            dimensionResource(R.dimen.keyguard_clock_top_margin) +
                            dimensionResource(clocksR.dimen.enhanced_smartspace_height) +
                            dimensionResource(R.dimen.smartspace_padding_vertical) * 2
                    // The clock parts also report where they really end, and the stack is kept below that.
                    val clockContent = remember { LargeClockContentBottom() }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val clockAreaHeight =
                            max(clockReserve, maxHeight * LARGE_CLOCK_WITH_NOTIFICATIONS_WEIGHT)
                                .coerceAtMost(maxHeight * LARGE_CLOCK_WITH_NOTIFICATIONS_MAX_SHARE)
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().height(clockAreaHeight)) {
                                CompositionLocalProvider(
                                    LocalLargeClockContentBottom provides clockContent
                                ) {
                                    when {
                                        customStyle != 0 ->
                                            CustomClockView(
                                                // Let a tall clock style take its full height
                                                Modifier.wrapContentHeight(
                                                        Alignment.Top,
                                                        unbounded = true,
                                                    )
                                                    .largeClockContentPart()
                                            )
                                        clockHidden -> { /* no clock */ }
                                        else -> LockscreenElement(Region.Clock.Large)
                                    }
                                }
                            }
                            NotificationsBelowClock(clockContent, Modifier.weight(1f))
                        }
                    }
                }
                scene(NarrowScenes.SmallClock) {
                    // Like iOS, the notifications under the small clock rest just above the lock icon.
                    CompositionLocalProvider(
                        LocalLockscreenStackAlignment provides Alignment.BottomCenter
                    ) {
                        val clock: @Composable () -> Unit = {
                            when {
                                customStyle != 0 -> CustomClockView()
                                clockHidden -> { /* no clock */ }
                                else -> LockscreenElement(Region.Clock.Small)
                            }
                        }
                        val player = this@LockscreenUpperRegionElementProvider.player
                        if (player != null) {
                            Column(Modifier.fillMaxSize()) {
                                with(player) {
                                    SmallClockColumn(
                                        notificationsActive = viewModel.isNotificationStackActive,
                                        clock = clock,
                                        media = { MediaCarousel(Modifier.align(Alignment.Start)) },
                                        notifications = { notificationsModifier ->
                                            Notifications(
                                                aodAlignment = Alignment.TopStart,
                                                modifier = notificationsModifier,
                                            )
                                        },
                                    )
                                }
                            }
                        } else {
                            Column {
                                clock()
                                MediaCarousel(Modifier.align(Alignment.Start))
                                Notifications(aodAlignment = Alignment.TopStart)
                            }
                        }
                    }
                }
            }
        }

        /**
         * The notifications under the large clock, moved down where the clock content reaches lower
         * than the clock area. The AOD burn-in shift is measured with a probe that moves like the date row.
         */
        @Composable
        private fun LockscreenScope<ContentScope>.NotificationsBelowClock(
            clockContent: LargeClockContentBottom,
            modifier: Modifier,
        ) {
            var top by remember { mutableFloatStateOf(Float.NaN) }
            var burnInTop by remember { mutableFloatStateOf(Float.NaN) }
            val gap = with(LocalDensity.current) { CLOCK_NOTIFICATIONS_GAP.toPx() }
            val contentBottom = clockContent.bottom
            val stackTopPadding =
                if (contentBottom == null || top.isNaN()) {
                    0.dp
                } else {
                    val burnIn =
                        if (burnInTop.isNaN()) 0f else (burnInTop - top).coerceAtLeast(0f)
                    with(LocalDensity.current) {
                        (contentBottom + burnIn + gap - top).coerceAtLeast(0f).toDp()
                    }
                }
            Box(modifier.onGloballyPositioned { top = it.positionInRoot().y }) {
                Box(
                    Modifier.burnInAware(isClock = false).onGloballyPositioned {
                        burnInTop = it.positionInRoot().y
                    }
                )
                // Center the AOD icons under the centered large clock.
                Notifications(
                    aodAlignment = Alignment.TopCenter,
                    stackTopPadding = stackTopPadding,
                )
            }
        }
    }

    /** The wide layouts are intended for tablets / foldables */
    inner class WideLayout(viewModel: LockscreenUpperRegionViewModel) : RegionLayout(viewModel) {
        @Composable
        override fun LockscreenScope<ContentScope>.Layout(modifier: Modifier) {
            val clockHidden = rememberClockHiddenState()
            val customStyle = rememberCustomClockStyle()

            val clockSize =
                logDecision("WideLayout: ClockSize") {
                    viewModel.evaluateClockSize {
                        when {
                            viewModel.shadeMode == ShadeMode.Dual ->
                                Decision(ClockSize.LARGE, "shadeMode == ShadeMode.Dual")
                            viewModel.isMediaVisible -> Decision(ClockSize.SMALL, "isMediaVisible")
                            else -> Decision(ClockSize.LARGE, "Default Case")
                        }
                    }
                }

            val isTwoColumn =
                logDecision("WideLayout: TwoColumn") {
                    when {
                        clockSize == ClockSize.SMALL -> Decision(true, "clockSize == SMALL")
                        !viewModel.isDozing &&
                            (viewModel.isNotificationStackActive || viewModel.isMediaVisible) -> {
                            Decision(
                                true,
                                "!isDozing && (isNotificationStackActive || isMediaVisible)",
                            )
                        }
                        viewModel.isDozing && viewModel.isHeadsUpNotificationActive ->
                            Decision(true, "isDozing && isHeadsUpNotificationActive")
                        viewModel.isDozing && viewModel.isPromotedNotificationActive ->
                            Decision(true, "isDozing && isPromotedNotificationActive")
                        else -> Decision(false, "Default Case")
                    }
                }

            var widePadding = dimensionResource(R.dimen.upper_region_wide_horizontal_padding)
            if (viewModel.shadeMode == ShadeMode.Dual) {
                widePadding =
                    max(
                        widePadding,
                        WindowInsets.safeContent
                            .asPaddingValues()
                            .calculateStartPadding(LocalLayoutDirection.current),
                    )
            }

            NestedScenes(
                sceneKey =
                    when {
                        !isTwoColumn -> WideScenes.CenteredClock
                        clockSize == ClockSize.LARGE -> WideScenes.TwoColumn.LargeClock
                        else -> WideScenes.TwoColumn.SmallClock
                    },
                transitions = {
                    from(from = WideScenes.CenteredClock, to = WideScenes.TwoColumn.LargeClock) {
                        configureClockCenteringTransition()
                    }
                    from(from = WideScenes.TwoColumn.LargeClock, to = WideScenes.CenteredClock) {
                        configureClockCenteringTransition()
                    }
                    from(from = WideScenes.CenteredClock, to = WideScenes.TwoColumn.SmallClock) {
                        configureClockSwitchTransition(
                            enter = { fadeSmallClock() },
                            exit = { fadeLargeClock() },
                        )
                    }
                    from(from = WideScenes.TwoColumn.SmallClock, to = WideScenes.CenteredClock) {
                        configureClockSwitchTransition(
                            enter = { fadeLargeClock() },
                            exit = { fadeSmallClock() },
                        )
                    }
                    from(
                        from = WideScenes.TwoColumn.LargeClock,
                        to = WideScenes.TwoColumn.SmallClock,
                    ) {
                        configureClockSwitchTransition(
                            enter = { fadeSmallClock() },
                            exit = { fadeLargeClock() },
                        )
                    }
                    from(
                        from = WideScenes.TwoColumn.SmallClock,
                        to = WideScenes.TwoColumn.LargeClock,
                    ) {
                        configureClockSwitchTransition(
                            enter = { fadeLargeClock() },
                            exit = { fadeSmallClock() },
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = widePadding).then(modifier),
                debugName = "WideLayout - Clocks",
            ) {
                scene(WideScenes.CenteredClock) {
                    LargeClockCenter_NotifsAlign(
                        clockHidden = clockHidden,
                        customStyle = customStyle,
                        notifAlignment =
                            when (viewModel.shadeMode) {
                                ShadeMode.Dual -> {
                                    if (viewModel.useDesktopStatusBar) Alignment.TopEnd
                                    else Alignment.TopStart
                                }
                                ShadeMode.Split -> Alignment.TopEnd
                                else -> {
                                    logger.wtf("WideLayout state is invalid")
                                    Alignment.TopCenter
                                }
                            }
                    )
                }
                scene(WideScenes.TwoColumn.LargeClock) {
                    when (viewModel.shadeMode) {
                        ShadeMode.Dual -> {
                            if (viewModel.useDesktopStatusBar) LargeClockStart_NotifsEnd_MediaEnd(clockHidden, customStyle)
                            else LargeClockEnd_NotifsStart_MediaStart(clockHidden, customStyle)
                        }
                        ShadeMode.Split -> LargeClockStart_NotifsEnd(clockHidden, customStyle)
                        else -> logger.wtf("WideLayout state is invalid")
                    }
                }
                scene(WideScenes.TwoColumn.SmallClock) {
                    when (viewModel.shadeMode) {
                        ShadeMode.Dual -> {
                            if (viewModel.useDesktopStatusBar) SmallClockStart_NotifsEnd_MediaEnd(clockHidden, customStyle)
                            else SmallClockStart_NotifsStart_MediaStart(clockHidden, customStyle)
                        }
                        ShadeMode.Split -> SmallClockStart_NotifsEnd_MediaStart(clockHidden, customStyle)
                        else -> logger.wtf("WideLayout state is invalid")
                    }
                }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.LargeClockCenter_NotifsAlign(
            clockHidden: Boolean,
            customStyle: Int,
            notifAlignment: Alignment,
            modifier: Modifier = Modifier,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().then(modifier),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    customStyle != 0 -> CustomClockView()
                    clockHidden -> { /* no clock */ }
                    else -> LockscreenElement(Region.Clock.Large)
                }
                AODNotifications(Modifier.align(notifAlignment))
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.LargeClockEnd_NotifsStart_MediaStart(
            clockHidden: Boolean,
            customStyle: Int,
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn {
                    MediaCarousel(Modifier.align(Alignment.Start))
                    Notifications(aodAlignment = Alignment.TopStart)
                }
                EndColumn(useLargeClockPadding = true) {
                    ClockAtPosition(clockHidden, customStyle, isLarge = true)
                }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.SmallClockStart_NotifsStart_MediaStart(
            clockHidden: Boolean,
            customStyle: Int,
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn {
                    ClockAtPosition(clockHidden, customStyle, isLarge = false)
                    MediaCarousel(Modifier.align(Alignment.Start))
                    Notifications(aodAlignment = Alignment.TopStart)
                }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.LargeClockStart_NotifsEnd(
            clockHidden: Boolean,
            customStyle: Int,
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn(useLargeClockPadding = true) {
                    ClockAtPosition(clockHidden, customStyle, isLarge = true)
                }
                EndColumn { Notifications(aodAlignment = Alignment.TopEnd) }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.LargeClockStart_NotifsEnd_MediaEnd(
            clockHidden: Boolean,
            customStyle: Int,
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn(useLargeClockPadding = true) {
                    ClockAtPosition(clockHidden, customStyle, isLarge = true)
                }
                EndColumn {
                    MediaCarousel(Modifier.align(Alignment.End))
                    Notifications(aodAlignment = Alignment.TopEnd)
                }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.SmallClockStart_NotifsEnd_MediaStart(
            clockHidden: Boolean,
            customStyle: Int,
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn {
                    ClockAtPosition(clockHidden, customStyle, isLarge = false)
                    MediaCarousel(Modifier.align(Alignment.Start))
                }
                EndColumn { Notifications(aodAlignment = Alignment.TopEnd) }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.SmallClockStart_NotifsEnd_MediaEnd(
            clockHidden: Boolean,
            customStyle: Int,
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn {
                    ClockAtPosition(clockHidden, customStyle, isLarge = false)
                }
                EndColumn {
                    MediaCarousel(Modifier.align(Alignment.End))
                    Notifications(aodAlignment = Alignment.TopEnd)
                }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.ClockAtPosition(
            clockHidden: Boolean,
            customStyle: Int,
            isLarge: Boolean
        ) {
            when {
                customStyle != 0 -> CustomClockView()
                clockHidden -> { /* no clock */ }
                else -> LockscreenElement(
                    if (isLarge) Region.Clock.Large else Region.Clock.Small
                )
            }
        }

        @Immutable
        inner class TwoColumnScope(private val rowScope: RowScope, private val columnPadding: Dp) {
            @Composable
            fun StartColumn(
                useLargeClockPadding: Boolean = false,
                modifier: Modifier = Modifier,
                content: @Composable ColumnScope.() -> Unit = {},
            ) {
                var paddingModifier =
                    if (useLargeClockPadding) Modifier.padding(end = columnPadding)
                    else Modifier.padding(horizontal = columnPadding)
                Column(
                    paddingModifier
                        .fillMaxWidth(0.5f)
                        .fillMaxHeight()
                        .graphicsLayer { translationX = viewModel.unfoldTranslations.start }
                        .then(modifier)
                ) {
                    content()
                }
            }

            @Composable
            fun EndColumn(
                useLargeClockPadding: Boolean = false,
                modifier: Modifier = Modifier,
                content: @Composable ColumnScope.() -> Unit = {},
            ) {
                var paddingModifier =
                    if (useLargeClockPadding) Modifier.padding(start = columnPadding)
                    else Modifier.padding(horizontal = columnPadding)
                Column(
                    paddingModifier
                        .fillMaxWidth(1f)
                        .fillMaxHeight()
                        .graphicsLayer { translationX = viewModel.unfoldTranslations.end }
                        .then(modifier)
                ) {
                    content()
                }
            }
        }

        @Composable
        private fun TwoColumn(
            modifier: Modifier = Modifier,
            content: @Composable TwoColumnScope.() -> Unit = {},
        ) {
            Row(modifier) {
                val padding = dimensionResource(R.dimen.upper_region_wide_column_horizontal_padding)
                with(TwoColumnScope(this@Row, padding)) { content() }
            }
        }
    }

    @Composable
    fun <T> logDecision(
        @CompileTimeConstant prefix: String,
        func: @Composable () -> Decision<T>,
    ): T {
        val decision = func()
        logger.i({ "$prefix: decision=$str1; reason=$str2" }) {
            str1 = "${decision.choice}"
            str2 = decision.reason
        }
        return decision.choice
    }

    @Composable
    private fun rememberClockHiddenState(): Boolean {
        var hidden by remember { mutableStateOf(false) }
        val contentResolver = context.contentResolver
        DisposableEffect(Unit) {
            fun update() {
                hidden = Settings.System.getInt(
                    contentResolver, Settings.System.LS_CLOCK_HIDE, 0
                ) != 0
            }
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { update() }
            }
            update()
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.LS_CLOCK_HIDE),
                false, observer,
            )
            onDispose { contentResolver.unregisterContentObserver(observer) }
        }
        return hidden
    }

    @Composable
    private fun rememberCustomClockStyle(): Int {
        var style by remember { mutableStateOf(0) }
        val contentResolver = context.contentResolver
        DisposableEffect(Unit) {
            fun update() {
                style = Settings.Secure.getInt(
                    contentResolver, ClockStyle.CLOCK_STYLE_KEY, 0
                )
            }
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { update() }
            }
            update()
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(ClockStyle.CLOCK_STYLE_KEY),
                false, observer,
            )
            onDispose { contentResolver.unregisterContentObserver(observer) }
        }
        return style
    }

    @Composable
    private fun CustomClockView(modifier: Modifier = Modifier) {
        AndroidView(
            // Preserve caller measurement/bounds modifiers while keeping the clock's natural height.
            modifier = modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true),
            factory = { ctx ->
                LayoutInflater.from(ctx).inflate(R.layout.keyguard_clock_style, null) as ClockStyle
            },
            update = { view ->
                view.onTimeChanged()
                view.requestLayout()
            },
        )
    }

    companion object {
        const val CLOCK_CENTERING_DURATION_MILLIS = 1000

        /** Share of the narrow upper region kept for the large clock when notifications show. */
        const val LARGE_CLOCK_WITH_NOTIFICATIONS_WEIGHT = 0.45f

        /** Most of the narrow upper region the large clock may take when notifications show. */
        const val LARGE_CLOCK_WITH_NOTIFICATIONS_MAX_SHARE = 0.7f

        /** Space kept between the large clock content and the notification stack below it. */
        val CLOCK_NOTIFICATIONS_GAP = 16.dp

        /** Room for the date and weather row below the large clock. */
        val DATE_WEATHER_ROW_RESERVE = 56.dp

        enum class LayoutType {
            WIDE,
            NARROW,
        }

        @Composable
        fun getLayoutDecision(shadeMode: ShadeMode): Decision<LayoutType> {
            return when (shadeMode) {
                ShadeMode.Single -> Decision(LayoutType.NARROW, "Single Shade")
                ShadeMode.Split -> Decision(LayoutType.WIDE, "Split Shade")
                ShadeMode.Dual -> {
                    with(LocalWindowSizeClass.current) {
                        val isWindowLarge =
                            isAtLeastBreakpoint(
                                WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
                                WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
                            )

                        if (isWindowLarge) {
                            Decision(LayoutType.WIDE, "Dual Shade && SizeClass >= Medium")
                        } else {
                            Decision(LayoutType.NARROW, "Dual Shade && SizeClass < Medium")
                        }
                    }
                }
            }
        }

        @Composable
        fun getLayoutType(shadeMode: ShadeMode): LayoutType {
            return getLayoutDecision(shadeMode).choice
        }
    }
}
