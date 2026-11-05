/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.penguin

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val LOCK_ICON_ON_TOP = "ls_lock_icon_top"

/** Like ColorOS, the lock screen's padlock sits at the top, under the status bar. */
@Composable
fun rememberLockIconOnTop(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() =
        Settings.System.getIntForUser(resolver, LOCK_ICON_ON_TOP, 1, UserHandle.USER_CURRENT) != 0
    var onTop by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    onTop = read()
                }
            }
        resolver.registerContentObserver(
            Settings.System.getUriFor(LOCK_ICON_ON_TOP),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return onTop
}
