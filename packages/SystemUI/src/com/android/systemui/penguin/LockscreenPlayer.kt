/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.penguin

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel

interface LockscreenPlayer {
    val isEnabled: Boolean

    val expansion: Float

    /** How far the player card and notifications have folded into the now bar, 0 to 1. */
    val nowBarFraction: Float

    /** Whether the player can fold, with the notifications, into the now bar right now. */
    val canFoldIntoNowBar: Boolean
        get() = false

    fun foldIntoNowBar() {}

    @Composable fun ObserveSettings()

    fun setMediaVisible(visible: Boolean)

    @Composable
    fun Card(
        viewModelFactory: MediaViewModel.Factory,
        behavior: MediaUiBehavior,
        modifier: Modifier,
    )

    @Composable
    fun ExpandedArt(
        alpha: () -> Float,
        covered: () -> Float,
        unlocking: () -> Float,
        cardAlpha: () -> Float,
    )

    @Composable
    fun ColumnScope.SmallClockColumn(
        notificationsActive: Boolean,
        clock: @Composable () -> Unit,
        media: @Composable ColumnScope.() -> Unit,
        notifications: @Composable (Modifier) -> Unit,
    )

    @Composable fun NowBar(modifier: Modifier)

    /** Touch handling over the folded artwork, which is drawn behind the lock screen. */
    @Composable fun ArtGestures()
}
