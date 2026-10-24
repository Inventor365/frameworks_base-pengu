/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.notifications.ui.viewmodel

import android.content.Context
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.UserActionResult.HideOverlay
import com.android.compose.animation.scene.UserActionResult.ReplaceByOverlay
import com.android.compose.animation.scene.UserActionResult.ShowOverlay
import com.android.compose.animation.scene.UserActionResult.ShowOverlay.HideCurrentOverlays
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.penguin.CosFeature
import com.android.systemui.penguin.CosFeatures
import com.android.systemui.penguin.CosShadeSwitch
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.ui.viewmodel.SceneContainerArea.EndHalf
import com.android.systemui.scene.ui.viewmodel.SceneContainerArea.TopEdgeEndHalf
import com.android.systemui.scene.ui.viewmodel.UserActionsViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Models the UI state for the user actions for navigating to other scenes or overlays. */
class NotificationsShadeOverlayActionsViewModel
@AssistedInject
constructor(@Application private val context: Context) : UserActionsViewModel() {

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        CosFeatures.state(context, CosFeature.Shade).collect { cosShade ->
            setActions(actions(cosShade))
        }
    }

    private fun actions(cosShade: Boolean): Map<UserAction, UserActionResult> = buildMap {
        put(Swipe.Up, HideOverlay(Overlays.NotificationsShade))
        put(Back, HideOverlay(Overlays.NotificationsShade))
        put(
            Swipe.Down(fromSource = EndHalf),
            ShowOverlay(
                Overlays.QuickSettingsShade,
                hideCurrentOverlays = HideCurrentOverlays.Some(Overlays.NotificationsShade),
            ),
        )
        put(
            Swipe.Down(fromSource = TopEdgeEndHalf),
            ShowOverlay(
                Overlays.QuickSettingsShade,
                hideCurrentOverlays = HideCurrentOverlays.Some(Overlays.NotificationsShade),
            ),
        )
        if (cosShade) {
            put(
                Swipe.Start,
                ReplaceByOverlay(Overlays.QuickSettingsShade, transitionKey = CosShadeSwitch),
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): NotificationsShadeOverlayActionsViewModel
    }
}
