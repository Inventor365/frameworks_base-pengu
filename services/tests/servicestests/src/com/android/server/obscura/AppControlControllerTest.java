/*
 * SPDX-FileCopyrightText: 2026 Saikiran
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.obscura;

import static android.app.ObscuraManager.SETTING_OBSCURA_CONFIG;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import androidx.test.filters.SmallTest;

import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Set;

@SmallTest
public class AppControlControllerTest {
    private final MockContentResolver mResolver = new MockContentResolver();
    private final RecordingSettingsProvider mProvider = new RecordingSettingsProvider();
    private AppControlController mController;

    private static class RecordingSettingsProvider extends FakeSettingsProvider {
        int writes;
        boolean failWrites;

        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            if ("PUT_secure".equals(method) && SETTING_OBSCURA_CONFIG.equals(arg)) {
                if (failWrites) throw new SecurityException("Write rejected for test");
                writes++;
            }
            return super.call(method, arg, extras);
        }
    }

    private AppControlController createController(int userId) throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        LauncherApps launcher = mock(LauncherApps.class);
        ApplicationInfo info = new ApplicationInfo();
        info.uid = UserHandle.getUid(userId, 10001);
        when(context.getUserId()).thenReturn(userId);
        when(context.getContentResolver()).thenReturn(mResolver);
        when(context.getPackageManager()).thenReturn(pm);
        when(context.getSystemServiceName(LauncherApps.class)).thenReturn(Context.LAUNCHER_APPS_SERVICE);
        when(context.getSystemService(Context.LAUNCHER_APPS_SERVICE)).thenReturn(launcher);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(info);
        when(launcher.getActivityList(anyString(), any(UserHandle.class)))
                .thenReturn(List.of(mock(LauncherActivityInfo.class)));
        AppControlController controller = new AppControlController(context, Set.of("android"));
        controller.init();
        return controller;
    }

    @Before
    public void setUp() throws Exception {
        FakeSettingsProvider.clearSettingsProvider();
        mResolver.addProvider(Settings.AUTHORITY, mProvider);
        mController = createController(UserHandle.USER_SYSTEM);
    }

    @After
    public void tearDown() {
        mController.destroy();
        FakeSettingsProvider.clearSettingsProvider();
    }

    private void configure(String pkg, int mode, List<String> scope) {
        mController.setAppConfig(pkg, false, false, true, true, new int[] {3003}, true,
                mode, scope, List.of("accessibility_enabled"));
    }

    private String savedConfig() {
        return Settings.Secure.getStringForUser(mResolver, SETTING_OBSCURA_CONFIG,
                UserHandle.USER_SYSTEM);
    }

    @Test
    public void snapshot_isStableAfterLaterUpdates() throws Exception {
        configure("bank", 1, List.of("root.tool"));
        String snapshot = mController.getConfig();
        configure("bank", 2, List.of("visible"));
        org.json.JSONObject config = new org.json.JSONObject(snapshot);
        assertThat(config.getJSONObject("app_scope_modes").getInt("bank")).isEqualTo(1);
        assertThat(config.getJSONObject("app_scope_lists").getJSONArray("bank").getString(0))
                .isEqualTo("root.tool");
        assertThat(mController.getAppScopeMode("bank")).isEqualTo(2);
    }

    @Test
    public void appUpdate_writesOnceAndAppliesAllControls() {
        configure("bank", 1, List.of("root.tool"));
        assertThat(mProvider.writes).isEqualTo(1);
        assertThat(mController.shouldHidePackageFromCaller("bank", "root.tool")).isTrue();
        assertThat(mController.isPackageDetached("bank")).isTrue();
        assertThat(mController.isPackageIsolated("bank")).isTrue();
        assertThat(mController.getRestrictedGids("bank")).asList().containsExactly(3003);
        assertThat(mController.isDataIsolationEnabled("bank")).isTrue();
        assertThat(mController.isSpoofSettingEnabled("bank", "enabled_accessibility_services"))
                .isTrue();
    }

    @Test
    public void appUpdate_failedWriteKeepsSavedAndActivePolicy() {
        configure("bank", 1, List.of("root.tool"));
        String before = savedConfig();
        mProvider.failWrites = true;
        assertThrows(SecurityException.class, () -> configure("bank", 2, List.of("other")));
        assertThat(savedConfig()).isEqualTo(before);
        assertThat(mController.getAppScopeMode("bank")).isEqualTo(1);
        assertThat(mController.getAppScopeList("bank")).containsExactly("root.tool");
    }

    @Test
    public void import_rejectsNestedInvalidValuesWithoutWriting() {
        configure("bank", 1, List.of("root.tool"));
        String before = savedConfig();
        for (String invalid : List.of("{\"hidden_pkgs\":[23]}",
                "{\"gid_restrictions\":{\"bank\":[-1]}}",
                "{\"app_scope_modes\":{\"bank\":9}}",
                "{\"app_scope_lists\":{\"bank\":[null]}}",
                "{\"spoof_settings_map\":{\"bank\":true}}", "{\"unknown\":[]}")) {
            assertThrows(IllegalArgumentException.class, () -> mController.importConfig(invalid));
            assertThat(savedConfig()).isEqualTo(before);
            assertThat(mController.getAppScopeMode("bank")).isEqualTo(1);
        }
        assertThat(mProvider.writes).isEqualTo(1);
    }

    @Test
    public void import_appliesBeforeObserverDispatch() {
        mController.importConfig("{\"app_scope_modes\":{\"bank\":1},"
                + "\"app_scope_lists\":{\"bank\":[\"other\"]}}");
        assertThat(mController.shouldHidePackageFromCaller("bank", "other")).isTrue();
    }

    @Test
    public void disabledScope_keepsSavedSelectionAcrossReload() throws Exception {
        configure("bank", 0, List.of("root.tool"));
        AppControlController reloaded = createController(UserHandle.USER_SYSTEM);
        try {
            assertThat(reloaded.getAppScopeList("bank")).containsExactly("root.tool");
            assertThat(reloaded.shouldHidePackageFromCaller("bank", "root.tool")).isFalse();
        } finally {
            reloaded.destroy();
        }
    }

    @Test
    public void reset_changesOnlySelectedAppsControls() {
        configure("bank", 1, List.of("root.tool"));
        configure("another", 1, List.of("bank"));
        mController.setAppConfig("bank", false, false, false, false, new int[0], false,
                0, List.of(), List.of());
        assertThat(mController.getAppScopeMode("bank")).isEqualTo(0);
        assertThat(mController.getAppScopeList("bank")).isEmpty();
        assertThat(mController.getEnabledSpoofSettings("bank")).isEmpty();
        assertThat(mController.getRestrictedGids("bank")).isNull();
        assertThat(mController.isPackageDetached("bank")).isFalse();
        assertThat(mController.isPackageIsolated("bank")).isFalse();
        assertThat(mController.isDataIsolationEnabled("bank")).isFalse();
        assertThat(mController.getAppScopeList("another")).containsExactly("bank");
    }

    @Test
    public void batchUpdate_keepsProfilesSeparate() throws Exception {
        configure("bank", 1, List.of("root.tool"));
        AppControlController profile = createController(10);
        try {
            profile.setAppConfig("bank", false, false, false, false, null, false,
                    2, List.of("visible"), List.of());
            assertThat(mController.getAppScopeMode("bank")).isEqualTo(1);
            assertThat(profile.getAppScopeMode("bank")).isEqualTo(2);
        } finally {
            profile.destroy();
        }
    }

    @Test
    public void batchUpdate_rejectsProtectedAppsAndUnsupportedSettings() {
        assertThrows(IllegalArgumentException.class, () -> configure("android", 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> mController.setAppConfig("bank",
                false, false, false, false, null, false, 0, List.of(), List.of("unsupported")));
        assertThat(mProvider.writes).isEqualTo(0);
    }
}
