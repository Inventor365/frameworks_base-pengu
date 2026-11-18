/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.obscura;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import com.android.internal.os.BackgroundThread;
import android.os.Binder;
import android.os.Parcel;
import android.util.SparseArray;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.app.HiddenNotificationInfo;
import com.android.internal.app.IHiddenNotificationListener;
import com.android.internal.app.IObscuraManager;
import com.android.server.NtServiceInjector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ObscuraService extends IObscuraManager.Stub implements IObscuraService {
    private static final String TAG = "Obscura";

    public static final String OBSCURA_PACKAGE = "com.android.settings";
    public static final Set<String> BLACKLISTED_PACKAGES = Set.of(
            "android",
            "android.media",
            "android.uid.system",
            "android.uid.shell",
            "android.uid.systemui",
            "com.android.shell",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "app.lawnchair",
            "app.lawnchair.playstore",
            "com.teslacoilsw.launcher",
            "com.actionlauncher.playstore",
            "com.android.permissioncontroller",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.providers.settings",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.webview",
            "com.google.android.providers.media.module"
    );

    private Context mContext;
    private final SparseArray<AppControlController> mControllers = new SparseArray<>();
    private final SparseArray<HiddenNotificationController> mNotifications = new SparseArray<>();

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
            mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_OBSCURA",
                    "Obscura requires MANAGE_OBSCURA");
        }
        return super.onTransact(code, data, reply, flags);
    }

    private AppControlController controller() {
        return controllerForUser(UserHandle.getCallingUserId());
    }

    private AppControlController controllerForUser(int userId) {
        if (mContext == null || userId < 0) return null;
        synchronized (mControllers) {
            AppControlController controller = mControllers.get(userId);
            if (controller == null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    Context userContext = mContext.createContextAsUser(UserHandle.of(userId), 0);
                    controller = new AppControlController(userContext, BLACKLISTED_PACKAGES);
                    mControllers.put(userId, controller);
                    controller.init();
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            return controller;
        }
    }

    private HiddenNotificationController notificationsForUser(int userId) {
        synchronized (mNotifications) {
            HiddenNotificationController notifications = mNotifications.get(userId);
            if (notifications == null) {
                notifications = new HiddenNotificationController();
                notifications.setPackageHiddenChecker(pkg -> isPackageHidden(pkg, userId));
                mNotifications.put(userId, notifications);
            }
            return notifications;
        }
    }

    private static final class Holder {
        private static final ObscuraService INSTANCE = new ObscuraService();
    }

    public static ObscuraService get() {
        return Holder.INSTANCE;
    }

    private ObscuraService() {
    }

    private final BroadcastReceiver mPackageRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getData() == null) return;
            String packageName = intent.getData().getSchemeSpecificPart();
            if (TextUtils.isEmpty(packageName)) return;
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;

            Slog.i(TAG, "Package removed: " + packageName + ", cleaning up");
            cleanupPackage(packageName, intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL));
        }
    };

    private void cleanupPackage(String packageName, int userId) {
        if (userId < 0) return;
        AppControlController controller = controllerForUser(userId);
        if (controller != null) controller.cleanupPackage(packageName);
        notificationsForUser(userId).clearNotificationsForPackage(packageName);
    }

    @Override
    public void systemReadyInternal() {
        Slog.d(TAG, "systemReady");
        mContext = NtServiceInjector.get().getContext();

        controllerForUser(UserHandle.USER_SYSTEM);

        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(mPackageRemovedReceiver, UserHandle.ALL, filter,
                null, BackgroundThread.getHandler());
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                if (userId < 0) return;
                synchronized (mControllers) {
                    AppControlController controller = mControllers.get(userId);
                    if (controller != null) controller.destroy();
                    mControllers.remove(userId);
                }
                synchronized (mNotifications) {
                    HiddenNotificationController notifications = mNotifications.get(userId);
                    if (notifications != null) notifications.destroy();
                    mNotifications.remove(userId);
                }
            }
        }, UserHandle.ALL, new IntentFilter(Intent.ACTION_USER_REMOVED), null,
                BackgroundThread.getHandler());
    }

    public static void systemReady() {
        ObscuraService instance = get();
        instance.systemReadyInternal();
        ServiceManager.addService(Context.OBSCURA_SERVICE, instance);
        Slog.i(TAG, "ObscuraService ready");
    }

    @Override
    public boolean isPackageHidden(String packageName) {
        if (controller() == null) return false;
        return controller().isPackageHidden(packageName);
    }

    @Override
    public void setPackageHidden(String packageName, boolean hidden) {
        controller().setPackageHidden(packageName, hidden);
    }

    @Override
    public boolean isPackageLauncherHidden(String packageName) {
        if (controller() == null) return false;
        return controller().isPackageLauncherHidden(packageName);
    }

    @Override
    public void setPackageLauncherHidden(String packageName, boolean hidden) {
        controller().setPackageLauncherHidden(packageName, hidden);
    }

    @Override
    public List<String> getHiddenPackages() {
        if (controller() == null) return java.util.Collections.emptyList();
        return controller().getHiddenPackages();
    }

    @Override
    public List<String> getLauncherHiddenPackages() {
        if (controller() == null) return java.util.Collections.emptyList();
        return controller().getLauncherHiddenPackages();
    }

    @Override
    public boolean isPackageDetached(String packageName) {
        if (controller() == null) return false;
        return controller().isPackageDetached(packageName);
    }

    @Override
    public void setPackageDetached(String packageName, boolean detached) {
        if (controller() != null) {
            controller().setPackageDetached(packageName, detached);
        }
    }

    @Override
    public List<String> getDetachedPackages() {
        if (controller() == null) return java.util.Collections.emptyList();
        return controller().getDetachedPackages();
    }

    @Override
    public List<String> getLockablePackages() {
        if (controller() == null) return java.util.Collections.emptyList();
        return controller().getLockablePackages();
    }

    @Override
    public void registerHiddenNotificationListener(IHiddenNotificationListener listener) {
        notificationsForUser(UserHandle.getCallingUserId()).registerListener(listener);
    }

    @Override
    public void unregisterHiddenNotificationListener(IHiddenNotificationListener listener) {
        notificationsForUser(UserHandle.getCallingUserId()).unregisterListener(listener);
    }

    @Override
    public List<HiddenNotificationInfo> getHiddenNotifications() {
        return notificationsForUser(UserHandle.getCallingUserId()).getHiddenNotifications();
    }

    @Override
    public void onHiddenNotificationPosted(HiddenNotificationInfo info) {
        if (info != null) notificationsForUser(info.userId).onHiddenNotificationPosted(info);
    }

    @Override
    public void onHiddenNotificationRemoved(String key) {
        notificationsForUser(UserHandle.getCallingUserId()).onHiddenNotificationRemoved(key);
    }

    @Override
    public boolean isPackageIsolated(String packageName) {
        if (controller() == null) return false;
        return controller().isPackageIsolated(packageName);
    }

    @Override
    public void isolatePackage(String packageName) {
        controller().setPackageIsolated(packageName, true);
    }

    @Override
    public void unisolatePackage(String packageName) {
        controller().setPackageIsolated(packageName, false);
    }

    @Override
    public List<String> getIsolatedPackages() {
        if (controller() == null) return java.util.Collections.emptyList();
        return controller().getIsolatedPackages();
    }

    @Override
    public void setRestrictedGids(String packageName, int[] gids) {
        controller().setRestrictedGids(packageName, gids);
    }

    @Override
    public int[] getRestrictedGids(String packageName) {
        if (controller() == null) return null;
        return controller().getRestrictedGids(packageName);
    }

    @Override
    public boolean isDataIsolationEnabled(String packageName) {
        if (controller() == null) return false;
        return controller().isDataIsolationEnabled(packageName);
    }

    @Override
    public void setDataIsolationEnabled(String packageName, boolean enabled) {
        controller().setDataIsolationEnabled(packageName, enabled);
    }

    @Override
    public boolean isSpoofSettingEnabled(String packageName, String settingKey) {
        if (controller() == null) return false;
        return controller().isSpoofSettingEnabled(packageName, settingKey);
    }

    @Override
    public void setSpoofSettingEnabled(String packageName, String settingKey, boolean enabled) {
        controller().setSpoofSettingEnabled(packageName, settingKey, enabled);
    }

    @Override
    public List<String> getEnabledSpoofSettings(String packageName) {
        if (controller() == null) return java.util.Collections.emptyList();
        return controller().getEnabledSpoofSettings(packageName);
    }

    @Override
    public String getSpoofedSetting(String callingPackage, String settingName) {
        if (controller() == null) return null;
        if (!controller().isSpoofSettingEnabled(callingPackage, settingName)) {
            return null;
        }
        return SettingsSpoofController.getSpoofedValue(settingName);
    }

    @Override
    public void launchHiddenApp(String packageName) {
        if (controller() != null) {
            controller().launchHiddenApp(packageName);
        }
    }

    @Override
    public int getAppScopeMode(String packageName) {
        if (controller() == null) return android.app.ObscuraManager.SCOPE_MODE_DISABLED;
        return controller().getAppScopeMode(packageName);
    }

    @Override
    public void setAppScopeMode(String packageName, int mode) {
        if (controller() != null) {
            controller().setAppScopeMode(packageName, mode);
        }
    }

    @Override
    public List<String> getAppScopeList(String packageName) {
        if (controller() == null) return java.util.Collections.emptyList();
        return controller().getAppScopeList(packageName);
    }

    @Override
    public void setAppScopeList(String packageName, List<String> packages) {
        if (controller() != null) {
            controller().setAppScopeList(packageName, packages);
        }
    }

    @Override
    public boolean shouldHidePackageFromCaller(String callerPackage, String targetPackage) {
        if (controller() == null) return false;
        return controller().shouldHidePackageFromCaller(callerPackage, targetPackage);
    }

    @Override
    public void setAppConfig(String packageName, boolean hidden, boolean launcherHidden,
            boolean detached, boolean isolated, int[] restrictedGids, boolean dataIsolation,
            int scopeMode, List<String> scopePackages, List<String> spoofSettings) {
        AppControlController controller = controller();
        if (controller == null) throw new IllegalStateException("Obscura is not ready");
        controller.setAppConfig(packageName, hidden, launcherHidden, detached, isolated,
                restrictedGids, dataIsolation, scopeMode, scopePackages, spoofSettings);
    }

    @Override
    public String getConfig() {
        AppControlController controller = controller();
        if (controller == null) throw new IllegalStateException("Obscura is not ready");
        return controller.getConfig();
    }

    @Override
    public void importConfig(String config) {
        AppControlController controller = controller();
        if (controller == null) throw new IllegalStateException("Obscura is not ready");
        controller.importConfig(config);
    }

    public boolean isPackageHidden(String packageName, int userId) {
        AppControlController controller = controllerForUser(userId);
        return controller == null ? false : controller.isPackageHidden(packageName);
    }

    public boolean isPackageLauncherHidden(String packageName, int userId) {
        AppControlController controller = controllerForUser(userId);
        return controller == null ? false : controller.isPackageLauncherHidden(packageName);
    }

    public boolean isPackageIsolated(String packageName, int userId) {
        AppControlController controller = controllerForUser(userId);
        return controller == null ? false : controller.isPackageIsolated(packageName);
    }

    public boolean isDataIsolationEnabled(String packageName, int userId) {
        AppControlController controller = controllerForUser(userId);
        return controller == null ? false : controller.isDataIsolationEnabled(packageName);
    }

    public int[] getRestrictedGids(String packageName, int userId) {
        AppControlController controller = controllerForUser(userId);
        return controller == null ? null : controller.getRestrictedGids(packageName);
    }

    public boolean shouldHidePackageFromCaller(String callerPackage, String targetPackage, int userId) {
        AppControlController controller = controllerForUser(userId);
        return controller == null ? false : controller.shouldHidePackageFromCaller(callerPackage, targetPackage);
    }

    public boolean isSpoofSettingEnabled(String packageName, String settingKey, int userId) {
        AppControlController controller = controllerForUser(userId);
        return controller == null ? false : controller.isSpoofSettingEnabled(packageName, settingKey);
    }
    public void onHiddenNotificationRemoved(String key, int userId) {
        notificationsForUser(userId).onHiddenNotificationRemoved(key);
    }

    @Override
    public String getSpoofedSettingForUser(String packageName, String settingName, int userId) {
        if (userId != UserHandle.getCallingUserId()
                && Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, "Obscura user");
        }
        AppControlController controller = controllerForUser(userId);
        if (controller == null || !controller.isSpoofSettingEnabled(packageName, settingName)) {
            return null;
        }
        return SettingsSpoofController.getSpoofedValue(settingName);
    }
    public boolean isPackageDetached(String packageName, int userId) {
        AppControlController controller = controllerForUser(userId);
        return controller != null && controller.isPackageDetached(packageName);
    }
}
