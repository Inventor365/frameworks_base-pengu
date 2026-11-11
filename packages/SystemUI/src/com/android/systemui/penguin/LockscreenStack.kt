/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.penguin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether the lock screen notification stack is expanded in place. Tapping the shelf expands it
 * at the bottom of the screen, where the thumb already is, instead of opening the shade: the whole
 * list then shows in one scrolling panel over the lock screen, like in the locked shade.
 */
object LockscreenStack {
    val expanded = MutableStateFlow(false)

    /** Set when the user folds the stack, so it can animate out before it collapses. */
    val collapseRequested = MutableStateFlow(false)

    /** Alpha of the whole stack while it animates in and out. */
    val alpha = MutableStateFlow(1f)

    /**
     * Top of the space the folded stack has under the clock, in window pixels, or NaN before it
     * is laid out. The expanded list rests below it and scrolls up over the clock.
     */
    @Volatile var stackAreaTop = Float.NaN

    /** Top of the folded stack itself, in window pixels: below the media player when it shows. */
    @JvmStatic @Volatile var stackTop = Float.NaN

    /**
     * Left edge and width of that space, in window pixels: the expanded list keeps the folded
     * stack's column (the right half in landscape, where the stack's view sits).
     */
    @Volatile var stackAreaLeft = Float.NaN
    @Volatile var stackAreaWidth = Float.NaN

    /** How much the expanded list covers the clock, 0 to 1; the clock region fades out with it. */
    var clockCover by mutableFloatStateOf(0f)

    /** How far the folded stack is dragged up, in pixels (0 or less), on its way to expanding. */
    var lift by mutableFloatStateOf(0f)

    /**
     * Whether the media artwork shows behind the lock screen. It is drawn in the lock screen's own
     * window, over the cards' window blur, so the cards draw their frost themselves meanwhile.
     */
    @JvmStatic
    @Volatile
    var artShowing = false
        private set

    /**
     * How far the open list is collapsed into the folded stack, 0 (laid out as a list) to 1 (every
     * card drawn into the folded card's place), as it folds and opens. Main thread.
     */
    @JvmStatic
    var restack = 0f
        set(value) {
            if (field == value) return
            field = value
            restackListeners.forEach(Runnable::run)
        }

    /**
     * Alpha of the collapsed stack before it hands over to the pill over the art: the cards
     * collapse, this fades them out, then the pill fades in. Main thread.
     */
    @JvmStatic
    var restackFade = 1f
        set(value) {
            if (field == value) return
            field = value
            restackListeners.forEach(Runnable::run)
        }

    /** Where the folded card was lifted to when the stack opened, so the list unfolds from there. */
    @JvmStatic @Volatile var restackLift = 0f

    /** How far the open list has scrolled, in pixels; the clock region moves up with it. */
    var coverScroll by mutableFloatStateOf(0f)

    private val restackListeners = CopyOnWriteArrayList<Runnable>()

    @JvmStatic
    fun addRestackListener(listener: Runnable) {
        restackListeners.add(listener)
    }

    @JvmStatic
    fun removeRestackListener(listener: Runnable) {
        restackListeners.remove(listener)
    }

    /**
     * Whether a pull down on the folded stack's cards folds them, with the media player, into the
     * now bar right now, and the fold itself. Set by the lock screen while a player can fold.
     */
    @JvmStatic @Volatile var canPullDown: () -> Boolean = { false }
    @JvmStatic @Volatile var pullDown: () -> Unit = {}

    private val collapseListeners = CopyOnWriteArrayList<Runnable>()
    private val artListeners = CopyOnWriteArrayList<Runnable>()

    /** Called on the main thread. */
    fun setArtShowing(showing: Boolean) {
        if (artShowing == showing) return
        artShowing = showing
        artListeners.forEach(Runnable::run)
    }

    /** Runs [listener] on the main thread each time [artShowing] changes. */
    @JvmStatic
    fun addArtListener(listener: Runnable) {
        artListeners.add(listener)
    }

    @JvmStatic
    fun removeArtListener(listener: Runnable) {
        artListeners.remove(listener)
    }

    @JvmStatic
    fun isExpanded(): Boolean = expanded.value

    @JvmStatic
    fun expand() {
        collapseRequested.value = false
        expanded.value = true
    }

    /** Folds the stack back with its animation. */
    fun requestCollapse() {
        if (expanded.value) collapseRequested.value = true
    }

    /**
     * Folds the stack back at once. With [keepHidden] the stack stays transparent, for the caller
     * to fade it back in.
     */
    fun collapse(keepHidden: Boolean = false) {
        collapseRequested.value = false
        alpha.value = if (keepHidden) 0f else 1f
        if (expanded.value) {
            expanded.value = false
            collapseListeners.forEach(Runnable::run)
        }
    }

    /** Runs [listener] each time the stack collapses, on the thread that collapsed it. */
    @JvmStatic
    fun addCollapseListener(listener: Runnable) {
        collapseListeners.add(listener)
    }
}
