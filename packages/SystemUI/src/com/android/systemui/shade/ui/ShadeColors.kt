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

package com.android.systemui.shade.ui

import android.content.Context
import android.graphics.Color
import android.os.UserHandle
import android.provider.Settings
import com.android.internal.graphics.ColorUtils
import com.android.systemui.common.shared.colors.SurfaceEffectColors
import com.android.systemui.penguin.CosFeature
import com.android.systemui.penguin.CosFeatures
import com.android.systemui.qs.shared.style.LiquidGlass
import com.android.systemui.qs.shared.style.LiquidGlassTune
import com.android.systemui.res.R
import kotlin.math.roundToInt

object ShadeColors {
    private val LIQUID_GLASS_DIM get() = LiquidGlassTune.color("dim", 0x52000000)
    private val LIQUID_GLASS_SCRIM get() = LiquidGlassTune.color("scrim", 0x8C000000.toInt())
    private val LIQUID_GLASS_PANEL get() = LiquidGlassTune.color("panel", 0x14000000)
    private val LIQUID_GLASS_DUAL_TONE get() = LiquidGlassTune.f("dual_tone", 0.12f)

    /**
     * Calculate notification shade panel color.
     *
     * @param context Context to resolve colors.
     * @param blurSupported Whether blur is enabled (can be off due to battery saver)
     * @param withScrim Whether to composite a scrim when blur is enabled (used by legacy shade).
     * @return color for the shade panel.
     */
    @JvmStatic
    fun shadePanel(context: Context, blurSupported: Boolean, withScrim: Boolean): Int {
        return if (blurSupported) {
            if (withScrim && LiquidGlass.enabled(context).value) {
                LIQUID_GLASS_DIM
            } else if (LiquidGlass.enabled(context).value) {
                LIQUID_GLASS_PANEL
            } else if (withScrim) {
                ColorUtils.compositeColors(
                    shadePanelStandard(context),
                    shadePanelScrimBehind(context),
                )
            } else {
                shadePanelStandard(context)
            }
        } else {
            shadePanelFallback(context)
        }
    }

    @JvmStatic
    fun notificationScrim(context: Context, blurSupported: Boolean): Int {
        return if (blurSupported) {
            if (isStockQsStyle(context) && !LiquidGlass.enabled(context).value) {
                notificationScrimStandard(context)
            } else {
                Color.TRANSPARENT
            }
        } else {
            notificationScrimFallback(context)
        }
    }

    /**
     * Opaque surface for notifications and their footer when the shade cannot blur: the tile
     * surface over the fallback panel, so both read as the same colour.
     */
    @JvmStatic
    fun fallbackCardSurface(context: Context): Int {
        return ColorUtils.compositeColors(
            SurfaceEffectColors.surfaceEffect1(context),
            shadePanelFallback(context),
        )
    }

    @JvmStatic
    fun shadePanelScrimBehind(context: Context): Int {
        if (LiquidGlass.enabled(context).value) return LIQUID_GLASS_SCRIM
        return context.resources.getColor(
            com.android.internal.R.color.shade_panel_scrim,
            context.theme,
        )
    }

    /**
     * Background color of the rounded cornered rect behind Notifications in Single- or SplitShade.
     */
    @JvmStatic
    fun classicShadeNotificationScrimBg(
        context: Context,
        blurSupported: Boolean,
        composited: Boolean,
    ): Int {
        val surfaceEffect0Color =
            context.resources.getColor(
                com.android.internal.R.color.customColorSurfaceEffect0,
                context.theme,
            )
        if (!composited) {
            // SplitShade (avoid adding the shade panel with scrim)
            return surfaceEffect0Color
        }
        val panel = shadePanel(context, blurSupported, withScrim = true)
        if (isStockQsStyle(context) && !LiquidGlass.enabled(context).value) {
            // Dual tone: tint the notification area so it stands apart from the QS panel.
            val tone =
                if (blurSupported) {
                    notificationScrimStandard(context)
                } else {
                    notificationScrimFallback(context)
                }
            return ColorUtils.compositeColors(tone, panel)
        }
        if (blurSupported && LiquidGlass.enabled(context).value && glassDualTone(context)) {
            // Dual tone on glass: a faint tint behind notifications.
            val alpha = (LIQUID_GLASS_DUAL_TONE.coerceIn(0f, 1f) * 255).roundToInt()
            val tone =
                ColorUtils.setAlphaComponent(context.getColor(R.color.notification_scrim_base), alpha)
            return ColorUtils.compositeColors(tone, panel)
        }
        return panel
    }

    /** Dual tone for Liquid Glass; the COS shade has its own card glass. */
    @JvmStatic
    private fun glassDualTone(context: Context): Boolean =
        LiquidGlass.dualTone(context).value && !CosFeatures.isOn(context, CosFeature.Shade)

    @JvmStatic
    private fun shadePanelStandard(context: Context): Int {
        val layerAbove =
            context.resources.getColor(com.android.internal.R.color.shade_panel_fg, context.theme)
        val layerBelow =
            context.resources.getColor(com.android.internal.R.color.shade_panel_bg, context.theme)
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    @JvmStatic
    private fun shadePanelFallback(context: Context): Int {
        return context.getColor(R.color.shade_panel_fallback)
    }

    /** Default or Stock QS style, which keep the AOSP dual-tone shade. */
    @JvmStatic
    private fun isStockQsStyle(context: Context): Boolean {
        val resolver = context.contentResolver
        if (Settings.Secure.getIntForUser(resolver, QS_ONE_UI_SETTING, 0,
                UserHandle.USER_CURRENT) != 0) {
            return false
        }
        val style = Settings.Secure.getIntForUser(resolver, QS_PANEL_STYLE_SETTING, 1,
            UserHandle.USER_CURRENT)
        return style == 0 || style == 6
    }

    const val QS_PANEL_STYLE_SETTING = "qs_panel_style"
    const val QS_ONE_UI_SETTING = "qs_oneui_experimental"

    @JvmStatic
    private fun notificationScrimStandard(context: Context): Int {
        return ColorUtils.setAlphaComponent(
            context.getColor(R.color.notification_scrim_base),
            (0.5f * 255).toInt(),
        )
    }

    @JvmStatic
    private fun notificationScrimFallback(context: Context): Int {
        return context.getColor(R.color.notification_scrim_fallback)
    }
}
