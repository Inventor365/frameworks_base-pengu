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

package com.android.systemui.qs.ui.viewmodel

import android.content.res.Resources
import android.graphics.Rect
import android.media.AudioManager
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Observer
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.Flags
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.desktop.domain.interactor.DesktopInteractor
import com.android.systemui.development.ui.viewmodel.BuildNumberViewModel
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.qs.panels.domain.interactor.QSPanelAppearanceInteractor
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.qs.tiles.dialog.AudioDetailsViewModel
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.core.StatusBarForDesktop
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimShape
import com.android.systemui.statusbar.ui.SystemBarUtilsState
import com.android.systemui.util.RingerModeTracker
import com.android.systemui.volume.dialog.domain.interactor.ExpandedAudioTileDetailsFeatureInteractor
import com.android.systemui.volume.panel.component.volume.domain.model.SliderType
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Models UI state used to render the content of the quick settings shade overlay.
 *
 * Different from [QuickSettingsShadeOverlayActionsViewModel], which only models user actions that
 * can be performed to navigate to other scenes.
 */
class QuickSettingsShadeOverlayContentViewModel
@AssistedInject
constructor(
    @Main private val mainDispatcher: CoroutineDispatcher,
    val desktopInteractor: DesktopInteractor,
    audioStreamSliderViewModelFactory: AudioStreamSliderViewModel.Factory,
    val audioDetailsViewModelFactory: AudioDetailsViewModel.Factory,
    val buildNumberViewModelFactory: BuildNumberViewModel.Factory,
    val expandedAudioTileDetailsFeatureInteractor: ExpandedAudioTileDetailsFeatureInteractor,
    val shadeInteractor: ShadeInteractor,
    val shadeModeInteractor: ShadeModeInteractor,
    val sceneInteractor: SceneInteractor,
    val notificationStackAppearanceInteractor: NotificationStackAppearanceInteractor,
    @ShadeDisplayAware private val resources: Resources,
    @ShadeDisplayAware systemBarUtilsState: SystemBarUtilsState,
    @Assisted private val volumeSliderCoroutineScope: CoroutineScope?,
    val toolbarViewModelFactory: ToolbarViewModel.Factory,
    private val blurConfig: BlurConfig,
    private val windowRootViewBlurInteractor: WindowRootViewBlurInteractor,
    private val qsPanelAppearanceInteractor: QSPanelAppearanceInteractor,
    private val audioManager: AudioManager,
    vibrator: Vibrator?,
    private val ringerModeTracker: RingerModeTracker,
) : HydratedActivatable() {

    /**
     * The Shade header can only be shown if usingDesktopStatusBar is disabled. This is because the
     * desktop status bar is always visible when usingDesktopStatusBar is enabled.
     */
    val showHeader: Boolean by
        if (StatusBarForDesktop.isEnabled) {
            desktopInteractor.useDesktopStatusBar
                .map { !it }
                .hydratedStateOf(
                    traceName = "showHeader",
                    initialValue = !desktopInteractor.useDesktopStatusBar.value,
                )
        } else {
            mutableStateOf(true)
        }

    /**
     * Whether the shade container transparency effect should be enabled (`true`), or whether to
     * render a fully-opaque shade container (`false`).
     */
    val isTransparencyEnabled: Boolean by
        if (Flags.notificationShadeBlur()) {
                windowRootViewBlurInteractor.isBlurCurrentlySupported
            } else {
                flowOf(false)
            }
            .hydratedStateOf(
                initialValue =
                    Flags.notificationShadeBlur() &&
                        windowRootViewBlurInteractor.isBlurCurrentlySupported.value
            )

    val statusBarHeightPx: Int by
        systemBarUtilsState.statusBarHeight.hydratedStateOf(
            traceName = "QuickSettingsShadeOverlayContentViewModel#statusBarHeight",
            initialValue = resources.getDimensionPixelSize(R.dimen.status_bar_height),
        )

    /**
     * Calculates the blur radius to apply to the overlay.
     *
     * @param transitionState The current transition state of the scene (from its `ContentScope`)
     * @return The blur radius to apply to the scene UI, in pixels.
     */
    fun calculateTargetBlurRadius(transitionState: TransitionState): Float {
        return when {
            !windowRootViewBlurInteractor.isBlurCurrentlySupported.value -> 0f
            Overlays.QuickSettingsShade !in transitionState.currentOverlays -> 0f
            Overlays.Bouncer in transitionState.currentOverlays -> blurConfig.maxBlurRadiusPx
            else -> 0f
        }
    }

    /** Whether AOSP's audio details (and the volume slider next to them) are enabled. */
    val isVolumeDetailsEnabled = expandedAudioTileDetailsFeatureInteractor.isEnabled()

    /**
     * The media volume slider. Built on first use, because besides the audio details feature a
     * Quick Settings style that draws the stock layout shows it as well.
     */
    val volumeSliderViewModel by lazy {
        if (volumeSliderCoroutineScope != null)
            audioStreamSliderViewModelFactory.create(
                AudioStreamSliderViewModel.FactoryAudioStreamWrapper(
                    SliderType.Stream(AudioStream(AudioManager.STREAM_MUSIC)).stream
                ),
                volumeSliderCoroutineScope,
            )
        else {
            null
        }
    }

    /** The ringer mode ([AudioManager.RINGER_MODE_NORMAL], _VIBRATE or _SILENT) of the device. */
    var ringerMode: Int by mutableIntStateOf(audioManager.ringerModeInternal)
        private set

    /** Whether the device can vibrate, so the ringer has a vibrate mode between ring and mute. */
    val supportsVibrateRinger: Boolean = vibrator?.hasVibrator() == true

    /** Cycles the ringer: ring, then vibrate (where supported), then mute, then ring again. */
    fun cycleRingerMode() {
        val next =
            when (audioManager.ringerModeInternal) {
                AudioManager.RINGER_MODE_NORMAL ->
                    if (supportsVibrateRinger) AudioManager.RINGER_MODE_VIBRATE
                    else AudioManager.RINGER_MODE_SILENT
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
        audioManager.setRingerModeInternal(next)
        ringerMode = next
    }

    override suspend fun onActivated() {
        coroutineScope {
            launch(mainDispatcher) {
                val observer = Observer<Int> { ringerMode = it }
                ringerModeTracker.ringerModeInternal.observeForever(observer)
                try {
                    awaitCancellation()
                } finally {
                    ringerModeTracker.ringerModeInternal.removeObserver(observer)
                }
            }
            launch {
                shadeInteractor.isShadeTouchable
                    .distinctUntilChanged()
                    .filter { !it }
                    .collect {
                        withContext(mainDispatcher) {
                            shadeInteractor.collapseQuickSettingsShade(
                                loggingReason = "device became non-interactive"
                            )
                        }
                    }
            }
        }
    }

    /**
     * Monitors changes to the shade mode that would make this overlay stale, and snaps to the
     * appropriate scene/overlay instead.
     *
     * This function must only run while the overlay is shown. Therefore, it shouldn't be part of
     * [onActivated()] while this overlay uses `alwaysCompose`.
     */
    suspend fun detectShadeModeChanges(): Nothing {
        shadeModeInteractor.shadeMode.collect { shadeMode ->
            withContext(mainDispatcher) {
                val loggingReason = "Fold while on quick settings shade"
                when (shadeMode) {
                    is ShadeMode.Single ->
                        sceneInteractor.snapToScene(Scenes.QuickSettings, loggingReason)

                    is ShadeMode.Split -> sceneInteractor.snapToScene(Scenes.Shade, loggingReason)

                    is ShadeMode.Dual -> Unit // Standard case, nothing to do
                }
            }
        }
    }

    /** Notifies that the bounds of the QuickSettings panel have changed. */
    fun onPanelShapeInWindowChanged(shape: ShadeScrimShape?) {
        qsPanelAppearanceInteractor.setQsPanelShape(shape)
        notificationStackAppearanceInteractor.setQsPanelShapeInWindow(shape)
    }

    /** Notifies that the bounds of the shade panel have changed. */
    fun onShadeOverlayBoundsChanged(bounds: androidx.compose.ui.geometry.Rect?) {
        val boundsRect =
            bounds?.let {
                Rect(
                    it.left.roundToInt(),
                    it.top.roundToInt(),
                    it.right.roundToInt(),
                    it.bottom.roundToInt(),
                )
            }
        shadeInteractor.setShadeOverlayBounds(boundsRect)
    }

    fun onScrimClicked() {
        shadeInteractor.collapseQuickSettingsShade(loggingReason = "shade scrim clicked")
    }

    @AssistedFactory
    interface Factory {
        fun create(
            volumeSliderCoroutineScope: CoroutineScope? = null
        ): QuickSettingsShadeOverlayContentViewModel
    }
}
