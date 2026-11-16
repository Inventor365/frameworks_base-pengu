/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.biometrics

import android.content.Context
import android.provider.Settings
import androidx.annotation.MainThread
import com.android.internal.util.custom.CustomUtils
import com.android.systemui.Dependency
import com.android.systemui.tuner.TunerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/** The UDFPS icon pack, shared by every view that draws the sensor icon. */
object UdfpsIconPack {
    const val PACKAGE = "com.crdroid.udfps.icons"
    val TUNER_KEY = "system:" + Settings.System.UDFPS_ICON

    private var installed: Boolean? = null
    private var customIconSelected: StateFlow<Boolean>? = null

    @JvmStatic
    fun isInstalled(context: Context): Boolean =
        installed ?: CustomUtils.isPackageInstalled(context, PACKAGE).also { installed = it }

    /** Whether an icon from the pack is chosen, from a single settings listener. */
    @MainThread
    fun customIconSelected(scope: CoroutineScope): StateFlow<Boolean> =
        customIconSelected
            ?: callbackFlow {
                    val tunable =
                        TunerService.Tunable { key, newValue ->
                            if (key == TUNER_KEY) {
                                trySend(TunerService.parseIntegerSwitch(newValue, false))
                            }
                        }
                    val tunerService = Dependency.get(TunerService::class.java)
                    tunerService.addTunable(tunable, TUNER_KEY)
                    awaitClose { tunerService.removeTunable(tunable) }
                }
                .stateIn(scope, SharingStarted.Eagerly, false)
                .also { customIconSelected = it }
}
