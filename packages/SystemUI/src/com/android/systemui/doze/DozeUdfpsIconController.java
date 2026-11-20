/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.doze;

import static android.hardware.biometrics.BiometricSourceType.FINGERPRINT;

import android.app.AlarmManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.os.SystemClock;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.settings.SecureSettings;

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
    /** How long a screen tap shows the icon (Penguin, single tap action "show icon"). */
    private static final long TAP_SHOW_MS = 8000;
    private static final String SCHEDULE_ALARM_TAG = "DozeUdfpsIconSchedule";
    private static final String TAP_ALARM_TAG = "DozeUdfpsIconTap";

    private final AmbientDisplayConfiguration mConfig;
    private final UserTracker mUserTracker;
    private final DozeParameters mDozeParameters;
    private final DozeHost mDozeHost;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final Handler mHandler;
    private final Runnable mUpdate = this::update;
    private final SecureSettings mSecureSettings;
    private final AlarmManager mAlarmManager;
    private final AlarmManager.OnAlarmListener mScheduleAlarm = this::onScheduleBoundary;
    private boolean mScheduleAlarmSet;
    // The phone suspends between frames in AOD, which stops uptime and Handler delays, so the
    // tap's timeout runs on elapsed realtime with a wake-up alarm.
    private final AlarmManager.OnAlarmListener mTapAlarm = this::update;
    /** Elapsed realtime until which a screen tap keeps the icon on. */
    private long mTapShownUntil;
    /** Whether a tap moved the doze machine to AOD for the icon. */
    private boolean mTapAod;

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
            @Main Handler handler,
            SecureSettings secureSettings,
            AlarmManager alarmManager) {
        mConfig = config;
        mUserTracker = userTracker;
        mDozeParameters = dozeParameters;
        mDozeHost = dozeHost;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mHandler = handler;
        mSecureSettings = secureSettings;
        mAlarmManager = alarmManager;
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
                    mDozeParameters.setUdfpsIconTapHandler(this::showForTap);
                    mRegistered = true;
                }
                updateScheduleAlarm();
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
        cancelScheduleAlarm();
        mTapShownUntil = 0;
        mAlarmManager.cancel(mTapAlarm);
        mTapAod = false;
        if (mRegistered) {
            mKeyguardUpdateMonitor.removeCallback(mCallback);
            mDozeParameters.setUdfpsIconTapHandler(null);
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
        if (!mKeyguardUpdateMonitor.isUdfpsEnrolled()) return false;
        return (isIconEnabled() && isInSchedule()) || isTapShown();
    }

    private int user() {
        return mUserTracker.getUserId();
    }

    /** Penguin: outside the chosen hours the icon stays off (unless a tap shows it). */
    private boolean isInSchedule() {
        if (!PenguinDozeTap.scheduleEnabled(mSecureSettings, user())) return true;
        return PenguinDozeTap.inWindow(PenguinDozeTap.minutesNow(System.currentTimeMillis()),
                PenguinDozeTap.start(mSecureSettings, user()),
                PenguinDozeTap.end(mSecureSettings, user()));
    }

    private boolean isTapShown() {
        return SystemClock.elapsedRealtime() < mTapShownUntil;
    }

    /** Shows the icon for TAP_SHOW_MS after a screen tap. False when there is no icon to show. */
    private boolean showForTap() {
        if (mMachine == null || mMachine.isUninitializedOrFinished()
                || !mKeyguardUpdateMonitor.isUdfpsEnrolled()
                // Not alwaysOnEnabled: that also counts the icon, which a tap shows outside
                // its hours.
                || mConfig.alwaysOnContentEnabled(user())) {
            return false;
        }
        mTapShownUntil = SystemClock.elapsedRealtime() + TAP_SHOW_MS;
        mHandler.post(mUpdate);
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, mTapShownUntil + 50,
                TAP_ALARM_TAG, mTapAlarm, mHandler);
        return true;
    }

    /** While dozing, re-check the icon when the schedule window opens or closes. */
    private void updateScheduleAlarm() {
        if (!PenguinDozeTap.scheduleEnabled(mSecureSettings, user()) || !isIconEnabled()) {
            cancelScheduleAlarm();
            return;
        }
        final long next = PenguinDozeTap.nextBoundary(System.currentTimeMillis(),
                PenguinDozeTap.start(mSecureSettings, user()),
                PenguinDozeTap.end(mSecureSettings, user()));
        // Two alarms a day at most; RTC_WAKEUP so the icon switches even while suspended.
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, next, SCHEDULE_ALARM_TAG, mScheduleAlarm,
                mHandler);
        mScheduleAlarmSet = true;
    }

    private void cancelScheduleAlarm() {
        if (mScheduleAlarmSet) {
            mAlarmManager.cancel(mScheduleAlarm);
            mScheduleAlarmSet = false;
        }
    }

    private void onScheduleBoundary() {
        mScheduleAlarmSet = false;
        update();
        if (mMachine != null && !mMachine.isUninitializedOrFinished()) {
            updateScheduleAlarm();
        }
    }

    private void update() {
        if (mMachine == null || mMachine.isUninitializedOrFinished()) {
            return;
        }
        mDozeParameters.setUdfpsIconState(isIconWanted(), mPulsing);

        // Penguin: with the icon off (or outside its hours) the screen sits in DOZE, so a tap
        // that shows the icon has to bring up AOD itself, and drop it again when it times out.
        final DozeMachine.State state = mMachine.getState();
        if (isTapShown()) {
            if (state == DozeMachine.State.DOZE && mDozeParameters.getAlwaysOn()
                    && !mDozeHost.isAlwaysOnSuppressed()) {
                mTapAod = true;
                mMachine.requestState(DozeMachine.State.DOZE_AOD);
            }
        } else if (mTapAod) {
            mTapAod = false;
            if (state == DozeMachine.State.DOZE_AOD && !mDozeParameters.getAlwaysOn()) {
                mMachine.requestState(DozeMachine.State.DOZE);
            }
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("DozeUdfpsIconController:");
        pw.println(" enabled=" + isIconEnabled());
        pw.println(" inSchedule=" + isInSchedule() + " tapShown=" + isTapShown()
                + " tapAod=" + mTapAod);
        pw.println(" active=" + mDozeParameters.isUdfpsIconActive());
        pw.println(" pulsing=" + mPulsing);
    }
}
