/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.doze;

import static android.hardware.biometrics.BiometricSourceType.FINGERPRINT;

import android.hardware.biometrics.BiometricSourceType;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.DozeParameters;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Keeps the screen in AOD with only the fingerprint icon while full AOD is off, as long as the
 * under-display sensor is listening. Without a listening sensor the screen goes dark instead.
 */
@DozeScope
public class DozeUdfpsIconController implements DozeMachine.Part {
    /** Fingerprint listening restarts briefly around screen off; don't blink the screen for it. */
    private static final long STOPPED_DEBOUNCE_MS = 1000;

    private final AmbientDisplayConfiguration mConfig;
    private final UserTracker mUserTracker;
    private final DozeParameters mDozeParameters;
    private final DozeHost mDozeHost;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final Handler mHandler;
    private final Runnable mUpdate = this::update;

    private DozeMachine mMachine;
    private boolean mRegistered;
    private boolean mPulsing;

    private final KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onBiometricRunningStateChanged(boolean running,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FINGERPRINT) {
                scheduleUpdate(running ? 0 : STOPPED_DEBOUNCE_MS);
            }
        }
    };

    @Inject
    DozeUdfpsIconController(
            AmbientDisplayConfiguration config,
            UserTracker userTracker,
            DozeParameters dozeParameters,
            DozeHost dozeHost,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            @Main Handler handler) {
        mConfig = config;
        mUserTracker = userTracker;
        mDozeParameters = dozeParameters;
        mDozeHost = dozeHost;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mHandler = handler;
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                if (!mRegistered) {
                    mKeyguardUpdateMonitor.registerCallback(mCallback);
                    mRegistered = true;
                }
                return;
            case FINISH:
                stop();
                return;
            default:
                break;
        }
        mPulsing = newState == DozeMachine.State.DOZE_REQUEST_PULSE
                || newState == DozeMachine.State.DOZE_PULSING
                || newState == DozeMachine.State.DOZE_PULSING_BRIGHT;
        // Hide the rest of the AOD content before the frame for the new state is drawn, then
        // correct the doze state once every part has seen this transition.
        mDozeParameters.setUdfpsIconState(isIconWanted(), mPulsing);
        scheduleUpdate(0);
    }

    @Override
    public void destroy() {
        stop();
    }

    private void stop() {
        mHandler.removeCallbacks(mUpdate);
        if (mRegistered) {
            mKeyguardUpdateMonitor.removeCallback(mCallback);
            mRegistered = false;
        }
        mPulsing = false;
        mDozeParameters.setUdfpsIconState(false, false);
    }

    private void scheduleUpdate(long delayMs) {
        mHandler.removeCallbacks(mUpdate);
        mHandler.postDelayed(mUpdate, delayMs);
    }

    private boolean isIconEnabled() {
        return mConfig.screenOffUdfpsIconEnabled(mUserTracker.getUserId());
    }

    private boolean isIconWanted() {
        return isIconEnabled()
                && mKeyguardUpdateMonitor.isUdfpsEnrolled()
                && mKeyguardUpdateMonitor.isFingerprintDetectionRunning();
    }

    private void update() {
        if (mMachine == null || mMachine.isUninitializedOrFinished()) {
            return;
        }
        final boolean wanted = isIconWanted();
        mDozeParameters.setUdfpsIconState(wanted, mPulsing);

        final DozeMachine.State state = mMachine.getState();
        if (wanted && state == DozeMachine.State.DOZE
                && !mDozeHost.isPowerSaveActive() && !mDozeHost.isAlwaysOnSuppressed()) {
            mMachine.requestState(DozeMachine.State.DOZE_AOD);
        } else if (!wanted && isIconEnabled() && state == DozeMachine.State.DOZE_AOD
                && !mDozeParameters.getAlwaysOnWithoutUdfpsIcon()) {
            mMachine.requestState(DozeMachine.State.DOZE);
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("DozeUdfpsIconController:");
        pw.println(" enabled=" + isIconEnabled());
        pw.println(" active=" + mDozeParameters.isUdfpsIconActive());
        pw.println(" pulsing=" + mPulsing);
    }
}
