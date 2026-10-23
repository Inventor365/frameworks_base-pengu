/*
 * Copyright (C) 2026 FundamentalOS
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

package com.android.systemui.common.ui.view

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Rect
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.qs.shared.style.GlassBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.DisposableHandle

/**
 * Keeps a [BackgroundBlurDrawable]'s alpha in step with the alpha its view is really drawn with.
 *
 * Blur regions are composited by SurfaceFlinger from the drawable's own alpha alone; the alpha of
 * the view's ancestors never reaches them. When the keyguard root view fades out (unlock, a shade
 * drag, the bouncer) a blurred lock icon or shortcut therefore keeps its full blur until the view
 * stops drawing, well after the icon itself has gone. Before every frame, fold the alphas from the
 * view up to the window root into the drawable instead.
 */
class BackgroundBlurAlphaSync(
    private val view: View,
    private val corner: () -> Float = { minOf(view.width, view.height) / 2f },
    private val drawable: () -> BackgroundBlurDrawable?,
) : ViewTreeObserver.OnPreDrawListener {
    private val location = IntArray(2)
    private var material = 0

    override fun onPreDraw(): Boolean {
        sync()
        return true
    }

    /** Applies the current effective alpha of [view] to the drawable. */
    fun sync() {
        val blur = drawable() ?: return
        var alpha = 1f
        var v: View? = view
        while (v != null && alpha > 0f) {
            alpha *= v.alpha * v.transitionAlpha
            v = v.parent as? View
        }
        val blurAlpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt()
        if (blur.alpha != blurAlpha) {
            blur.alpha = blurAlpha
        }
        val cos = GlassBackdrop.cos.value
        if (material != (if (cos) 1 else 0)) {
            material = if (cos) 1 else 0
            blur.setMaterial(material)
        }
        publish(cos && blur.isVisible && view.isShown, alpha)
    }

    private fun publish(shown: Boolean, alpha: Float) {
        if (!shown) {
            GlassBackdrop.update(this, null)
            return
        }
        view.getLocationInWindow(location)
        val left = location[0].toFloat()
        val top = location[1].toFloat()
        GlassBackdrop.update(
            this,
            GlassBackdrop.Region(
                Rect(left, top, left + view.width, top + view.height),
                corner(),
                alpha.coerceIn(0f, 1f),
            ),
        )
    }

    /**
     * Starts syncing before every frame. Call while [view] is attached; dispose the handle before
     * it detaches (the observer is reset on detach).
     */
    fun start(): DisposableHandle {
        val observer = view.viewTreeObserver
        observer.addOnPreDrawListener(this)
        sync()
        return DisposableHandle {
            (if (observer.isAlive) observer else view.viewTreeObserver).removeOnPreDrawListener(this)
            GlassBackdrop.update(this, null)
        }
    }
}
