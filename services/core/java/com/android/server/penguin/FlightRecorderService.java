/*
 * Copyright (C) 2026 PenguinOS
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

package com.android.server.penguin;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BugreportManager;
import android.os.BugreportParams;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;

/**
 * Drives the flight recorder (penguin_flightlog) from its Laboratory switch, and offers a bug
 * report after a restart the recorder saw no shutdown for: a hang, kernel panic or watchdog reset.
 */
public final class FlightRecorderService extends SystemService {
    private static final String TAG = "FlightRecorder";

    /** Settings.Global switch written by Laboratory > System. */
    private static final String SETTING = "penguin_flightlog";
    /** Read by the recorder's init script. */
    private static final String ENABLED_PROP = "persist.sys.penguin.flightlog";
    /** Set by the recorder when the last session ended without a shutdown. */
    private static final String UNCLEAN_PROP = "sys.penguin.flightlog.unclean";

    private static final String ACTION_REPORT = "com.android.server.penguin.action.FLIGHTLOG_REPORT";
    private static final int NOTIFICATION_ID = 0x50464c47;
    /** When to look for an unclean session after an unlock; the recorder takes about a second. */
    private static final long[] UNLOCK_CHECKS_MS = {1000, 3000, 10000, 30000, 90000};

    private final Handler mHandler = BackgroundThread.getHandler();
    private boolean mNotified;

    public FlightRecorderService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            getContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(SETTING), false,
                    new ContentObserver(mHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            syncEnabled();
                        }
                    }, UserHandle.USER_ALL);
            syncEnabled();
        } else if (phase == PHASE_BOOT_COMPLETED) {
            getContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    captureReport();
                }
            }, new IntentFilter(ACTION_REPORT), null, mHandler, Context.RECEIVER_NOT_EXPORTED);
            mHandler.post(this::maybeNotify);
        }
    }

    @Override
    public void onUserUnlocked(TargetUser user) {
        // The recorder starts once credential storage is unlocked and only then finds out how the
        // last session ended. It sets the property natively, which raises no change callback here,
        // so look again for a while after the unlock.
        for (long delay : UNLOCK_CHECKS_MS) {
            mHandler.postDelayed(this::maybeNotify, delay);
        }
    }

    private boolean isEnabled() {
        return Settings.Global.getInt(getContext().getContentResolver(), SETTING, 0) != 0;
    }

    private void syncEnabled() {
        final boolean enabled = isEnabled();
        final String have = SystemProperties.get(ENABLED_PROP, "");
        // Never switched on: leave the property unset so init has nothing to clear at boot.
        if (!enabled && have.isEmpty()) return;
        final String want = enabled ? "1" : "0";
        if (!want.equals(have)) {
            SystemProperties.set(ENABLED_PROP, want);
        }
        if (!enabled) {
            getContext().getSystemService(NotificationManager.class)
                    .cancelAsUser(TAG, NOTIFICATION_ID, UserHandle.ALL);
        }
    }

    private void maybeNotify() {
        if (mNotified || !isEnabled() || !"1".equals(SystemProperties.get(UNCLEAN_PROP))) {
            return;
        }
        mNotified = true;
        final Context context = getContext();
        final PendingIntent capture = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_REPORT).setPackage(context.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE);
        final String text = context.getString(R.string.penguin_flightlog_unclean_text);
        final Notification notification =
                new Notification.Builder(context, SystemNotificationChannels.DEVELOPER_IMPORTANT)
                        .setSmallIcon(R.drawable.ic_lock_bugreport)
                        .setColor(context.getColor(R.color.system_notification_accent_color))
                        .setContentTitle(context.getString(R.string.penguin_flightlog_unclean_title))
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setContentIntent(capture)
                        .setAutoCancel(true)
                        .build();
        context.getSystemService(NotificationManager.class)
                .notifyAsUser(TAG, NOTIFICATION_ID, notification, UserHandle.ALL);
        Slog.i(TAG, "Last session ended without a shutdown; offering a bug report");
    }

    private void captureReport() {
        final Context context = getContext();
        context.getSystemService(NotificationManager.class)
                .cancelAsUser(TAG, NOTIFICATION_ID, UserHandle.ALL);
        try {
            context.getSystemService(BugreportManager.class).requestBugreport(
                    new BugreportParams(BugreportParams.BUGREPORT_MODE_INTERACTIVE),
                    context.getString(R.string.penguin_flightlog_report_title),
                    context.getString(R.string.penguin_flightlog_report_description));
        } catch (RuntimeException e) {
            Slog.w(TAG, "Could not start a bug report", e);
        }
    }
}
