/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.penguin

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The lock screen notification stack's alpha while folding into the now bar. It covers the whole
 * view, shelf included, which the stack's own lock screen fade does not.
 */
object NowBarStack {
    val alpha = MutableStateFlow(1f)
}
