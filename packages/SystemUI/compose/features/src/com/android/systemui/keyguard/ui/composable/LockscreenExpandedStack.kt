/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.composable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.notifications.ui.composable.ScrollingNotificationPanel
import com.android.systemui.penguin.LockscreenStack
import com.android.systemui.res.R
import com.android.systemui.scene.session.shared.SessionStorage
import com.android.systemui.scene.session.ui.composable.SaveableSession
import com.android.systemui.scene.session.ui.composable.Session
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The lock screen notification stack expanded in place, as one scrolling panel over the whole lock
 * screen. It rests at the bottom above the shortcuts, where the thumb already is, in the space the
 * folded stack has under the clock, and scrolls up over the clock as far as the top of the screen,
 * the same panel the notification shade scrolls with. Pulled down at its top it follows the finger
 * back down into the folded stack.
 */
@SysUISingleton
class LockscreenExpandedStack
@Inject
constructor(
    private val stackScrollView: Lazy<NotificationScrollView>,
    private val placeholderViewModelFactory: NotificationsPlaceholderViewModel.Factory,
    private val jankMonitor: InteractionJankMonitor,
) {
    // Its own session, so the panel does not share its scroll state with the shade.
    private val session: SaveableSession =
        object : SaveableSession, Session by Session(SessionStorage()) {
            @Composable
            override fun <T : Any> rememberSaveableSession(
                vararg inputs: Any?,
                saver: Saver<T, out Any>,
                key: String?,
                init: () -> T,
            ): T = rememberSession(key, inputs = inputs, init = init)
        }

    @Composable
    fun ContentScope.ExpandedStack(modifier: Modifier = Modifier) {
        val expanded by LockscreenStack.expanded.collectAsStateWithLifecycle()
        val collapseRequested by LockscreenStack.collapseRequested.collectAsStateWithLifecycle()
        // 0 = folded into the shelf, 1 = expanded in place. The panel rises and fades in on a
        // soft spring, and fades out before it folds.
        val expansion = remember { Animatable(0f) }
        // How far the list is pulled down past its top, following the finger on its way back
        // into the folded stack at the bottom.
        var pull by remember { mutableFloatStateOf(0f) }
        // After folding out, the folded stack fades in instead of popping in.
        var fadeInFolded by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        val foldDistance = with(density) { FoldPull.toPx() }
        // 0 = laid out as a list, 1 = every card drawn into the folded card's place.
        val restack = remember { Animatable(0f) }
        LaunchedEffect(restack) {
            snapshotFlow { restack.value }.collect { LockscreenStack.restack = it.coerceIn(0f, 1f) }
        }
        LaunchedEffect(expanded, collapseRequested) {
            when {
                expanded && !collapseRequested -> {
                    pull = 0f
                    // Like iOS, the cards unfold from the folded card: it shows at once, the
                    // others spread out from behind it.
                    LockscreenStack.restackFade = 1f
                    restack.snapTo(1f)
                    expansion.snapTo(1f)
                    restack.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 380f))
                    LockscreenStack.restackLift = 0f
                }
                expanded -> {
                    // The cards collapse into the folded card's place. The folded stack is the
                    // same cards: it takes over at once while they stay drawn collapsed until it
                    // has laid them out there, so nothing fades or blinks.
                    restack.animateTo(1f, spring(dampingRatio = 1f, stiffness = 520f))
                    // The collapsed list fades out, then the folded stack fades in: a single card
                    // would otherwise snap from its expanded to its folded size.
                    animate(1f, 0f, animationSpec = tween(StackFadeMillis)) { value, _ ->
                        LockscreenStack.restackFade = value
                    }
                    fadeInFolded = true
                    LockscreenStack.collapse()
                }
                else -> {
                    expansion.snapTo(0f)
                    LockscreenStack.restackLift = 0f
                    LockscreenStack.clockCover = 0f
                    LockscreenStack.coverScroll = 0f
                    if (fadeInFolded) {
                        fadeInFolded = false
                        // Hand over to the folded stack: the cards ease from where they were
                        // drawn collapsed into its layout as it settles, and its pill fades in.
                        restack.animateTo(
                            0f,
                            tween(HandOffMillis, easing = FastOutSlowInEasing),
                        )
                    }
                    restack.snapTo(0f)
                    LockscreenStack.restack = 0f
                    LockscreenStack.restackFade = 1f
                }
            }
        }
        LaunchedEffect(expansion) {
            snapshotFlow {
                    // The pull dims the list; as it restacks it comes back to full, so the
                    // folded card takes over at full strength.
                    expansion.value.coerceIn(0f, 1f) *
                        (1f -
                            (pull / foldDistance * PullFade).coerceIn(0f, MaxPullFade) *
                                (1f - restack.value.coerceIn(0f, 1f)))
                }
                .collect { alpha ->
                    if (LockscreenStack.expanded.value) {
                        LockscreenStack.alpha.value = alpha
                    }
                }
        }
        if (!expanded) return

        // The stack's space and column are measured folded: rotating folds the list back.
        val orientation = LocalConfiguration.current.orientation
        val openedIn = remember { orientation }
        LaunchedEffect(orientation) { if (orientation != openedIn) LockscreenStack.collapse() }
        val foldVelocity = with(density) { FoldVelocity.toPx() }
        val scope = rememberCoroutineScope()
        // A fresh scroll position each time the list opens: it starts at its top, under the clock.
        val scrollState = remember { ScrollState(0) }
        val clockFade = with(density) { ClockFade.toPx() }
        LaunchedEffect(scrollState) {
            snapshotFlow {
                    // The clock region moves up with the list and fades as it goes; it comes back
                    // down as the list folds.
                    val open = 1f - restack.value.coerceIn(0f, 1f)
                    Pair(
                        (scrollState.value / clockFade).coerceIn(0f, 1f) * open,
                        scrollState.value * open,
                    )
                }
                .collect { (cover, scroll) ->
                    LockscreenStack.clockCover = cover
                    LockscreenStack.coverScroll = scroll
                }
        }
        val pullDownToFold = remember {
            object : NestedScrollConnection {
                var settle: Job? = null

                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || pull <= 0f || available.y >= 0f) {
                        return Offset.Zero
                    }
                    // Pushed back up while pulled: the list follows before it scrolls again.
                    settle?.cancel()
                    val delta = max(available.y, -pull)
                    pull += delta
                    return Offset(0f, delta)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput || available.y == 0f) {
                        return Offset.Zero
                    }
                    // Like iOS, an upward swipe past the end of the list (or on a list too short
                    // to scroll) stays with the list instead of unlocking.
                    if (available.y < 0f) return available
                    // Pulled down past the top of the list: it follows the finger down.
                    settle?.cancel()
                    pull += available.y
                    return Offset(0f, available.y)
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity =
                    // An upward fling left over at the end of the list does not unlock either.
                    if (available.y < 0f) Velocity(0f, available.y) else Velocity.Zero

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (pull <= 0f) return Velocity.Zero
                    val from = pull
                    settle =
                        if (from > foldDistance || available.y > foldVelocity) {
                            // Far or fast enough: the cards collapse into the folded stack from
                            // where they are.
                            LockscreenStack.requestCollapse()
                            null
                        } else {
                            scope.launch {
                                animate(
                                    from,
                                    0f,
                                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                                ) { value, _ ->
                                    pull = value
                                }
                            }
                        }
                    return available
                }
            }
        }
        val placeholderViewModel =
            rememberViewModel("LockscreenExpandedStack") {
                placeholderViewModelFactory.create(Scenes.Lockscreen)
            }
        // The NSSL is clipped to the whole window, re-sent every frame: the list scrolls up under
        // the status bar and its cards' window blur needs no clip of its own. The scroll panel's
        // own scrim reports null whenever it is unplaced during a relayout.
        var windowBounds by remember { mutableStateOf<ShadeScrimBounds?>(null) }
        LaunchedEffect(placeholderViewModel) {
            try {
                while (true) {
                    withFrameNanos {}
                    windowBounds?.let(placeholderViewModel::onScrimBoundsChanged)
                }
            } finally {
                placeholderViewModel.onScrimBoundsChanged(null)
            }
        }
        val context = LocalContext.current
        // The list rests in the space the folded stack has under the clock and scrolls up over it,
        // as far as the top of the screen.
        val restingTop =
            with(density) {
                val statusBar = SystemBarUtils.getStatusBarHeight(context).toDp() + TopGap
                // Where the folded stack starts: below the media player when it shows.
                val stackTop =
                    listOf(LockscreenStack.stackAreaTop, LockscreenStack.stackTop)
                        .filterNot { it.isNaN() }
                        .maxOrNull()
                if (stackTop == null) statusBar else maxOf(statusBar, stackTop.toDp())
            }
        // The folded stack's column, so the list stays inside the stack's view.
        val columnLeft =
            LockscreenStack.stackAreaLeft.let { if (it.isNaN()) 0 else it.roundToInt() }
        val columnWidth =
            LockscreenStack.stackAreaWidth.let {
                if (it.isNaN() || it <= 0f) null else with(density) { it.toDp() }
            }
        val bottomSpace =
            dimensionResource(R.dimen.keyguard_affordance_vertical_offset) +
                dimensionResource(R.dimen.keyguard_affordance_fixed_height) +
                Gap
        Box(
            modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val bounds = it.boundsInWindow()
                    windowBounds =
                        ShadeScrimBounds(
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom,
                        )
                }
                // Tapping anywhere around the list folds it back.
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = LockscreenStack::requestCollapse,
                )
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Bottom))
                .padding(bottom = bottomSpace)
        ) {
            ScrollingNotificationPanel(
                tag = "LockscreenExpanded",
                shadeSession = session,
                stackScrollView = stackScrollView.get(),
                viewModel = placeholderViewModel,
                notificationRulesParentViewModel = null,
                jankMonitor = jankMonitor,
                shouldPunchHoleBehindScrim = false,
                isTransparencyEnabled = false,
                stackTopPadding = 0.dp,
                stackBottomPadding = { 0.dp },
                shouldIncludeHeadsUpSpace = false,
                shouldDrawScrimBackground = false,
                contentScrollState = scrollState,
                // The space above the list scrolls with it, so the list can be dragged wherever
                // its cards are, also once they have scrolled up over the clock.
                aboveNotifications = { Spacer(Modifier.height(restingTop)) },
                onEmptySpaceClick = LockscreenStack::requestCollapse,
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .then(
                            if (columnWidth != null) Modifier.width(columnWidth)
                            else Modifier.fillMaxWidth()
                        )
                        // A layout offset, so the NSSL follows the placeholder as it moves.
                        .offset { IntOffset(columnLeft, pull.roundToInt()) }
                        .nestedScroll(pullDownToFold),
            )
        }
    }

    private companion object {
        /** How far the list has to be pulled down from its top to fold on release. */
        val FoldPull = 96.dp
        /** A downward fling this fast folds the pulled list, whatever the distance. */
        val FoldVelocity = 1000.dp
        /** How far the list scrolls, taking the clock region up with it, before it has faded. */
        val ClockFade = 160.dp
        /** The pulled list fades by this much per [FoldPull], down to [MaxPullFade]. */
        const val PullFade = 0.35f
        const val MaxPullFade = 0.6f
        val Gap = 16.dp
        /** Clears the lock icon under the status bar. */
        val TopGap = 48.dp
        /** How long the cards stay drawn collapsed while the folded stack lays them out. */
        const val HandOffMillis = 320
        const val StackFadeMillis = 160
    }
}
