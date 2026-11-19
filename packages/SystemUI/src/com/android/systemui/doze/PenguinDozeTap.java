/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.doze;

import com.android.systemui.util.settings.SecureSettings;

import java.util.Calendar;

/**
 * PenguinOS screen-off single tap and fingerprint icon schedule settings, written by the
 * Laboratory (Styles). The tap itself comes from the doze tap sensor, which only runs while
 * {@code doze_tap_gesture} is on.
 */
public final class PenguinDozeTap {
    /** What a single tap on the dark screen does. Unset means {@link #AMBIENT}. */
    public static final String ACTION = "penguin_doze_tap_action";
    public static final int NONE = 0;
    /** Shows the screen-off fingerprint icon for a few seconds. */
    public static final int SHOW_ICON = 1;
    /** Shows the ambient display; a tap on it then wakes the screen. */
    public static final int AMBIENT = 2;
    public static final int WAKE = 3;

    /** 1 to show the screen-off fingerprint icon only between the start and end times. */
    public static final String ICON_SCHEDULE = "penguin_doze_udfps_icon_schedule";
    /** Minutes after midnight. */
    public static final String ICON_START = "penguin_doze_udfps_icon_start";
    public static final String ICON_END = "penguin_doze_udfps_icon_end";
    public static final int DEFAULT_START = 7 * 60;
    public static final int DEFAULT_END = 23 * 60;

    private PenguinDozeTap() {}

    public static int action(SecureSettings settings, int user) {
        final int action = settings.getIntForUser(ACTION, AMBIENT, user);
        return action >= NONE && action <= WAKE ? action : AMBIENT;
    }

    public static boolean scheduleEnabled(SecureSettings settings, int user) {
        return settings.getIntForUser(ICON_SCHEDULE, 0, user) != 0;
    }

    public static int start(SecureSettings settings, int user) {
        return clamp(settings.getIntForUser(ICON_START, DEFAULT_START, user));
    }

    public static int end(SecureSettings settings, int user) {
        return clamp(settings.getIntForUser(ICON_END, DEFAULT_END, user));
    }

    /** Whether {@code now} (minutes after midnight) is inside [start, end), which may wrap. */
    public static boolean inWindow(int now, int start, int end) {
        if (start == end) return true;
        return start < end ? now >= start && now < end : now >= start || now < end;
    }

    /** The next time the window opens or closes after {@code nowMillis}. */
    public static long nextBoundary(long nowMillis, int start, int end) {
        final Calendar c = Calendar.getInstance();
        long best = Long.MAX_VALUE;
        for (int minutes : new int[] {start, end}) {
            c.setTimeInMillis(nowMillis);
            c.set(Calendar.HOUR_OF_DAY, minutes / 60);
            c.set(Calendar.MINUTE, minutes % 60);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= nowMillis) c.add(Calendar.DAY_OF_YEAR, 1);
            best = Math.min(best, c.getTimeInMillis());
        }
        return best;
    }

    public static int minutesNow(long nowMillis) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMillis);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static int clamp(int minutes) {
        return Math.max(0, Math.min(24 * 60 - 1, minutes));
    }
}
