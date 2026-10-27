/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.display;

import static android.hardware.display.DisplayManager.APP_HDR_PLAYBACK_MODE_DEFAULT;
import static android.hardware.display.DisplayManager.APP_HDR_PLAYBACK_MODE_HDR_ALLOWED;
import static android.hardware.display.DisplayManager.APP_HDR_PLAYBACK_MODE_SDR_ONLY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Manages per-application HDR playback mode overrides for {@link DisplayManagerService}.
 *
 * <p>Policies are persisted per user in {@link Settings.Secure#APP_HDR_PLAYBACK_POLICIES} by
 * package name and cached in memory by both package name and application ID for lock-light lookups
 * on display capability, HDR headroom, and surface configuration paths.
 *
 * <p>An app decides between its HDR and SDR pipelines when it starts playback, from the display
 * capabilities it saw, and the window headroom clamp cannot reach PQ/HLG video layers. So an
 * explicit mode change force-stops the package, and its next launch sees the new capabilities.
 */
final class AppHdrPolicyController {
    private static final String TAG = "AppHdrPolicyController";
    private static final int MAX_PACKAGE_NAME_LENGTH = 255;

    interface SettingsAdapter {
        @Nullable
        String getPolicies(int userId);

        void putPolicies(@Nullable String raw, int userId);
    }

    interface UidResolver {
        int getPackageUid(@NonNull String packageName, int userId);
    }

    interface PackageStopper {
        void forceStopPackage(@NonNull String packageName, int userId);
    }

    private final Context mContext;
    private final SettingsAdapter mSettingsAdapter;
    private final UidResolver mUidResolver;
    private final PackageStopper mPackageStopper;
    private final Runnable mOnPolicyChanged;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArrayMap<String, Integer> mPackageModes = new ArrayMap<>();

    @GuardedBy("mLock")
    private final SparseIntArray mAppIdModes = new SparseIntArray();

    @GuardedBy("mLock")
    private int mCurrentUserId = UserHandle.USER_SYSTEM;

    private volatile boolean mHasOverrides = false;

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            final String action = intent.getAction();
            if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
                onPackagesChanged(null);
                return;
            }
            final Uri data = intent.getData();
            final String pkg = data != null ? data.getSchemeSpecificPart() : null;
            onPackagesChanged(pkg);
        }
    };

    AppHdrPolicyController(@NonNull Context context, @NonNull Runnable onPolicyChanged) {
        this(
                context,
                new SettingsAdapter() {
                    @Override
                    public String getPolicies(int userId) {
                        return Settings.Secure.getStringForUser(
                                context.getContentResolver(),
                                Settings.Secure.APP_HDR_PLAYBACK_POLICIES,
                                userId);
                    }

                    @Override
                    public void putPolicies(String raw, int userId) {
                        Settings.Secure.putStringForUser(
                                context.getContentResolver(),
                                Settings.Secure.APP_HDR_PLAYBACK_POLICIES,
                                raw,
                                userId);
                    }
                },
                new UidResolver() {
                    @Override
                    public int getPackageUid(@NonNull String packageName, int userId) {
                        final PackageManager pm = context.getPackageManager();
                        if (pm == null) {
                            return Process.INVALID_UID;
                        }
                        try {
                            return pm.getPackageUidAsUser(packageName, 0, userId);
                        } catch (PackageManager.NameNotFoundException ignored) {
                            try {
                                return pm.getPackageUidAsUser(
                                        packageName, PackageManager.MATCH_ANY_USER, userId);
                            } catch (PackageManager.NameNotFoundException e) {
                                return Process.INVALID_UID;
                            }
                        }
                    }
                },
                (packageName, userId) -> {
                    final ActivityManager am = context.getSystemService(ActivityManager.class);
                    if (am == null) {
                        return;
                    }
                    final long token = Binder.clearCallingIdentity();
                    try {
                        am.forceStopPackageAsUser(packageName, userId);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                },
                onPolicyChanged);
    }

    @VisibleForTesting
    AppHdrPolicyController(
            @NonNull Context context,
            @NonNull SettingsAdapter settingsAdapter,
            @NonNull UidResolver uidResolver,
            @NonNull PackageStopper packageStopper,
            @NonNull Runnable onPolicyChanged) {
        mContext = context;
        mSettingsAdapter = settingsAdapter;
        mUidResolver = uidResolver;
        mPackageStopper = packageStopper;
        mOnPolicyChanged = onPolicyChanged;
    }

    void start(int initialUserId, @NonNull Handler handler) {
        synchronized (mLock) {
            mCurrentUserId = initialUserId;
            reloadFromSettingsLocked();
        }
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(
                mPackageReceiver, UserHandle.ALL, packageFilter, null, handler);

        final IntentFilter externalFilter =
                new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        mContext.registerReceiverAsUser(
                mPackageReceiver, UserHandle.ALL, externalFilter, null, handler);
    }

    void onUserSwitching(int newUserId) {
        final boolean changed;
        synchronized (mLock) {
            if (mCurrentUserId == newUserId) {
                return;
            }
            mCurrentUserId = newUserId;
            changed = reloadFromSettingsLocked();
        }
        if (changed) {
            mOnPolicyChanged.run();
        }
    }

    void onSettingChanged() {
        final boolean changed;
        synchronized (mLock) {
            changed = reloadFromSettingsLocked();
        }
        if (changed) {
            mOnPolicyChanged.run();
        }
    }

    @VisibleForTesting
    void onPackagesChanged(@Nullable String packageName) {
        final boolean changed;
        synchronized (mLock) {
            if (mPackageModes.isEmpty()) {
                return;
            }
            if (packageName != null && !mPackageModes.containsKey(packageName)) {
                return;
            }
            changed = rebuildAppIdMapLocked();
        }
        if (changed) {
            mOnPolicyChanged.run();
        }
    }

    void setModeForPackage(
            @NonNull String packageName, @DisplayManager.AppHdrPlaybackMode int mode) {
        if (!isValidPackageName(packageName)) {
            throw new IllegalArgumentException("Invalid package name: " + packageName);
        }
        if (!isValidMode(mode)) {
            throw new IllegalArgumentException("Invalid HDR playback mode: " + mode);
        }
        final String normalizedPkg = packageName.trim();
        final boolean changed;
        final int userId;
        synchronized (mLock) {
            final int currentMode =
                    mPackageModes.getOrDefault(normalizedPkg, APP_HDR_PLAYBACK_MODE_DEFAULT);
            if (currentMode == mode) {
                return;
            }
            if (mode == APP_HDR_PLAYBACK_MODE_DEFAULT) {
                mPackageModes.remove(normalizedPkg);
            } else {
                mPackageModes.put(normalizedPkg, mode);
            }
            mHasOverrides = !mPackageModes.isEmpty();
            rebuildAppIdMapLocked();
            final String serialized = serializePolicies(mPackageModes);
            mSettingsAdapter.putPolicies(serialized, mCurrentUserId);
            userId = mCurrentUserId;
            changed = true;
        }
        if (changed) {
            mOnPolicyChanged.run();
            restartPackage(normalizedPkg, userId);
        }
    }

    @DisplayManager.AppHdrPlaybackMode
    int getModeForPackage(@NonNull String packageName) {
        if (!isValidPackageName(packageName)) {
            throw new IllegalArgumentException("Invalid package name: " + packageName);
        }
        synchronized (mLock) {
            return mPackageModes.getOrDefault(packageName.trim(), APP_HDR_PLAYBACK_MODE_DEFAULT);
        }
    }

    void resetAllModes() {
        final boolean changed;
        final String[] resetPackages;
        final int userId;
        synchronized (mLock) {
            changed = !mPackageModes.isEmpty() || mAppIdModes.size() > 0;
            resetPackages = mPackageModes.keySet().toArray(new String[0]);
            userId = mCurrentUserId;
            mPackageModes.clear();
            mAppIdModes.clear();
            mHasOverrides = false;
            mSettingsAdapter.putPolicies("", mCurrentUserId);
        }
        if (changed) {
            mOnPolicyChanged.run();
        }
        for (String pkg : resetPackages) {
            restartPackage(pkg, userId);
        }
    }

    /**
     * Force-stops an application package so that its next launch starts from the new policy.
     * System UIDs are never restricted by {@link #getModeForUid}, so they are left running.
     */
    private void restartPackage(@NonNull String packageName, int userId) {
        final int uid = mUidResolver.getPackageUid(packageName, userId);
        if (uid < Process.FIRST_APPLICATION_UID) {
            return;
        }
        Slog.i(TAG, "Force-stopping " + packageName + " (u" + userId
                + ") so it relaunches with its new HDR playback mode");
        try {
            mPackageStopper.forceStopPackage(packageName, userId);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to force-stop " + packageName
                    + "; the HDR mode applies from its next launch", e);
        }
    }

    @NonNull
    String[] getPackagesWithOverrides() {
        synchronized (mLock) {
            final int size = mPackageModes.size();
            if (size == 0) {
                return new String[0];
            }
            final String[] result = new String[size];
            for (int i = 0; i < size; i++) {
                result[i] = mPackageModes.keyAt(i);
            }
            return result;
        }
    }

    @NonNull
    ArrayMap<String, Integer> getPackageModesSnapshot() {
        synchronized (mLock) {
            return new ArrayMap<>(mPackageModes);
        }
    }

    @DisplayManager.AppHdrPlaybackMode
    int getModeForUid(int uid) {
        if (!mHasOverrides || uid < Process.FIRST_APPLICATION_UID) {
            return APP_HDR_PLAYBACK_MODE_DEFAULT;
        }
        final int appId = UserHandle.getAppId(uid);
        synchronized (mLock) {
            return mAppIdModes.get(appId, APP_HDR_PLAYBACK_MODE_DEFAULT);
        }
    }

    @GuardedBy("mLock")
    private boolean reloadFromSettingsLocked() {
        final String raw = mSettingsAdapter.getPolicies(mCurrentUserId);
        final ArrayMap<String, Integer> parsed = parsePolicies(raw);
        final boolean packagesChanged = !mPackageModes.equals(parsed);
        mPackageModes.clear();
        mPackageModes.putAll(parsed);
        mHasOverrides = !mPackageModes.isEmpty();
        final boolean appIdsChanged = rebuildAppIdMapLocked();
        return packagesChanged || appIdsChanged;
    }

    @GuardedBy("mLock")
    private boolean rebuildAppIdMapLocked() {
        final SparseIntArray nextAppIdModes = new SparseIntArray();
        final int size = mPackageModes.size();
        for (int i = 0; i < size; i++) {
            final String pkg = mPackageModes.keyAt(i);
            final int mode = mPackageModes.valueAt(i);
            if (mode == APP_HDR_PLAYBACK_MODE_DEFAULT) {
                continue;
            }
            final int uid = mUidResolver.getPackageUid(pkg, mCurrentUserId);
            if (uid < Process.FIRST_APPLICATION_UID) {
                continue;
            }
            final int appId = UserHandle.getAppId(uid);
            final int existing = nextAppIdModes.get(appId, APP_HDR_PLAYBACK_MODE_DEFAULT);
            // If multiple packages share an appId, SDR_ONLY takes precedence over HDR_ALLOWED.
            if (existing == APP_HDR_PLAYBACK_MODE_SDR_ONLY) {
                continue;
            }
            nextAppIdModes.put(appId, mode);
        }

        boolean changed = mAppIdModes.size() != nextAppIdModes.size();
        if (!changed) {
            for (int i = 0; i < nextAppIdModes.size(); i++) {
                if (mAppIdModes.keyAt(i) != nextAppIdModes.keyAt(i)
                        || mAppIdModes.valueAt(i) != nextAppIdModes.valueAt(i)) {
                    changed = true;
                    break;
                }
            }
        }
        mAppIdModes.clear();
        for (int i = 0; i < nextAppIdModes.size(); i++) {
            mAppIdModes.put(nextAppIdModes.keyAt(i), nextAppIdModes.valueAt(i));
        }
        return changed;
    }

    static boolean isValidMode(int mode) {
        return mode == APP_HDR_PLAYBACK_MODE_DEFAULT
                || mode == APP_HDR_PLAYBACK_MODE_HDR_ALLOWED
                || mode == APP_HDR_PLAYBACK_MODE_SDR_ONLY;
    }

    static boolean isValidPackageName(@Nullable String packageName) {
        if (packageName == null) {
            return false;
        }
        final String trimmed = packageName.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_PACKAGE_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            final char c = trimmed.charAt(i);
            final boolean validChar =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '.'
                            || c == '_';
            if (!validChar) {
                return false;
            }
        }
        return trimmed.indexOf('.') > 0 && !trimmed.endsWith(".");
    }

    @NonNull
    static ArrayMap<String, Integer> parsePolicies(@Nullable String raw) {
        final ArrayMap<String, Integer> result = new ArrayMap<>();
        if (TextUtils.isEmpty(raw)) {
            return result;
        }
        final String[] entries = raw.split(";");
        for (String entry : entries) {
            if (TextUtils.isEmpty(entry)) {
                continue;
            }
            final int eqIdx = entry.indexOf('=');
            if (eqIdx <= 0 || eqIdx != entry.lastIndexOf('=') || eqIdx == entry.length() - 1) {
                Slog.w(TAG, "Ignoring malformed app HDR policy entry: " + entry);
                continue;
            }
            final String pkg = entry.substring(0, eqIdx).trim();
            final String modeStr = entry.substring(eqIdx + 1).trim();
            if (!isValidPackageName(pkg)) {
                Slog.w(TAG, "Ignoring invalid package name in app HDR policy: " + pkg);
                continue;
            }
            final int mode;
            try {
                mode = Integer.parseInt(modeStr);
            } catch (NumberFormatException e) {
                Slog.w(TAG, "Ignoring invalid mode in app HDR policy: " + entry);
                continue;
            }
            if (!isValidMode(mode)) {
                Slog.w(TAG, "Ignoring out-of-range mode in app HDR policy: " + entry);
                continue;
            }
            if (mode == APP_HDR_PLAYBACK_MODE_DEFAULT) {
                result.remove(pkg);
            } else {
                result.put(pkg, mode);
            }
        }
        return result;
    }

    @NonNull
    static String serializePolicies(@NonNull Map<String, Integer> policies) {
        if (policies.isEmpty()) {
            return "";
        }
        final List<String> keys = new ArrayList<>(policies.keySet());
        Collections.sort(keys);
        final StringBuilder sb = new StringBuilder();
        for (String pkg : keys) {
            if (!isValidPackageName(pkg)) {
                continue;
            }
            final Integer modeObj = policies.get(pkg);
            if (modeObj == null) {
                continue;
            }
            final int mode = modeObj;
            if (mode == APP_HDR_PLAYBACK_MODE_DEFAULT || !isValidMode(mode)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(pkg.trim()).append('=').append(mode);
        }
        return sb.toString();
    }

    @NonNull
    static String modeToString(@DisplayManager.AppHdrPlaybackMode int mode) {
        switch (mode) {
            case APP_HDR_PLAYBACK_MODE_DEFAULT:
                return "DEFAULT";
            case APP_HDR_PLAYBACK_MODE_HDR_ALLOWED:
                return "HDR_ALLOWED";
            case APP_HDR_PLAYBACK_MODE_SDR_ONLY:
                return "SDR_ONLY";
            default:
                return "UNKNOWN(" + mode + ")";
        }
    }

    void dump(@NonNull PrintWriter pw) {
        pw.println();
        pw.println("App HDR Playback Policies:");
        synchronized (mLock) {
            pw.println("  mCurrentUserId=" + mCurrentUserId);
            pw.println("  mHasOverrides=" + mHasOverrides);
            pw.println("  Package overrides (" + mPackageModes.size() + "):");
            for (int i = 0; i < mPackageModes.size(); i++) {
                pw.println("    " + mPackageModes.keyAt(i) + " -> "
                        + modeToString(mPackageModes.valueAt(i)));
            }
            pw.println("  Resolved appId overrides (" + mAppIdModes.size() + "):");
            for (int i = 0; i < mAppIdModes.size(); i++) {
                pw.println("    appId=" + mAppIdModes.keyAt(i) + " -> "
                        + modeToString(mAppIdModes.valueAt(i)));
            }
        }
    }
}
