/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.composable.elements

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * Where the large clock content (face and date row) ends, in root coordinates.
 */
@Stable
class LargeClockContentBottom {
    private val bottoms = mutableStateMapOf<Any, Float>()

    /** The lowest bottom edge of the reported parts, or null when nothing is reported. */
    val bottom: Float?
        get() = bottoms.values.maxOrNull()

    internal fun report(part: Any, bottom: Float) {
        if (bottoms[part] != bottom) bottoms[part] = bottom
    }

    internal fun remove(part: Any) {
        bottoms.remove(part)
    }
}

/** The collector for the large clock that is being laid out, if its bottom matters. */
val LocalLargeClockContentBottom = compositionLocalOf<LargeClockContentBottom?> { null }

/**
 * Reports the bottom edge of this part of the large clock to [LocalLargeClockContentBottom].
 */
@Composable
fun Modifier.largeClockContentPart(): Modifier {
    val collector = LocalLargeClockContentBottom.current ?: return this
    val part = remember { Any() }
    DisposableEffect(collector) { onDispose { collector.remove(part) } }
    return onGloballyPositioned { collector.report(part, it.positionInRoot().y + it.size.height) }
}
