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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Process;
import android.os.UserHandle;
import android.testing.TestableContext;
import android.util.ArrayMap;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class AppHdrPolicyControllerTest {

    private static final String PKG_YOUTUBE = "com.google.android.youtube";
    private static final String PKG_NETFLIX = "com.netflix.mediaclient";
    private static final String PKG_UNINSTALLED = "com.example.uninstalled.player";

    private static final int UID_YOUTUBE = 10150;
    private static final int UID_NETFLIX = 10151;
    private static final int UID_OTHER = 10199;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());

    private final ArrayMap<Integer, String> mUserPolicies = new ArrayMap<>();
    private final ArrayMap<String, Integer> mPackageUids = new ArrayMap<>();
    private final List<String> mStoppedPackages = new ArrayList<>();
    private AtomicInteger mCallbackCount;
    private AppHdrPolicyController mController;

    @Before
    public void setUp() {
        mUserPolicies.clear();
        mPackageUids.clear();
        mPackageUids.put(PKG_YOUTUBE, UID_YOUTUBE);
        mPackageUids.put(PKG_NETFLIX, UID_NETFLIX);
        mStoppedPackages.clear();

        mCallbackCount = new AtomicInteger(0);
        mController = new AppHdrPolicyController(
                mContext,
                new AppHdrPolicyController.SettingsAdapter() {
                    @Override
                    public String getPolicies(int userId) {
                        return mUserPolicies.get(userId);
                    }

                    @Override
                    public void putPolicies(String raw, int userId) {
                        mUserPolicies.put(userId, raw);
                    }
                },
                (packageName, userId) -> mPackageUids.getOrDefault(packageName, Process.INVALID_UID),
                (packageName, userId) -> mStoppedPackages.add(packageName + "/u" + userId),
                () -> mCallbackCount.incrementAndGet());
    }

    @Test
    public void testDefaultModeAndFastPath() {
        assertEquals(APP_HDR_PLAYBACK_MODE_DEFAULT,
                mController.getModeForPackage(PKG_YOUTUBE));
        assertEquals(APP_HDR_PLAYBACK_MODE_DEFAULT,
                mController.getModeForUid(UID_YOUTUBE));
        assertEquals(APP_HDR_PLAYBACK_MODE_DEFAULT,
                mController.getModeForUid(Process.SYSTEM_UID));
        assertEquals(0, mController.getPackagesWithOverrides().length);
    }

    @Test
    public void testSetAndGetModesByPackageAndUid() {
        mController.setModeForPackage(PKG_YOUTUBE, APP_HDR_PLAYBACK_MODE_SDR_ONLY);
        mController.setModeForPackage(PKG_NETFLIX, APP_HDR_PLAYBACK_MODE_HDR_ALLOWED);

        assertEquals(APP_HDR_PLAYBACK_MODE_SDR_ONLY,
                mController.getModeForPackage(PKG_YOUTUBE));
        assertEquals(APP_HDR_PLAYBACK_MODE_SDR_ONLY,
                mController.getModeForUid(UID_YOUTUBE));

        assertEquals(APP_HDR_PLAYBACK_MODE_HDR_ALLOWED,
                mController.getModeForPackage(PKG_NETFLIX));
        assertEquals(APP_HDR_PLAYBACK_MODE_HDR_ALLOWED,
                mController.getModeForUid(UID_NETFLIX));

        assertEquals(APP_HDR_PLAYBACK_MODE_DEFAULT,
                mController.getModeForUid(UID_OTHER));

        List<String> packages = Arrays.asList(mController.getPackagesWithOverrides());
        assertEquals(2, packages.size());
        assertTrue(packages.contains(PKG_YOUTUBE));
        assertTrue(packages.contains(PKG_NETFLIX));
        assertEquals(2, mCallbackCount.get());
    }

    @Test
    public void testSetDefaultRemovesOverride() {
        mController.setModeForPackage(PKG_YOUTUBE, APP_HDR_PLAYBACK_MODE_SDR_ONLY);
        assertEquals(APP_HDR_PLAYBACK_MODE_SDR_ONLY,
                mController.getModeForUid(UID_YOUTUBE));

        mController.setModeForPackage(PKG_YOUTUBE, APP_HDR_PLAYBACK_MODE_DEFAULT);
        assertEquals(APP_HDR_PLAYBACK_MODE_DEFAULT,
                mController.getModeForPackage(PKG_YOUTUBE));
        assertEquals(APP_HDR_PLAYBACK_MODE_DEFAULT,
                mController.getModeForUid(UID_YOUTUBE));
        assertEquals(0, mController.getPackagesWithOverrides().length);
    }

    @Test
    public void testResetAllOverrides() {
        mController.setModeForPackage(PKG_YOUTUBE, APP_HDR_PLAYBACK_MODE_SDR_ONLY);
        mController.setModeForPackage(PKG_NETFLIX, APP_HDR_PLAYBACK_MODE_HDR_ALLOWED);
        assertEquals(2, mController.getPackagesWithOverrides().length);

        mController.resetAllModes();

        assertEquals(0, mController.getPackagesWithOverrides().length);
        assertEquals(APP_HDR_PLAYBACK_MODE_DEFAULT,
                mController.getModeForPackage(PKG_YOUTUBE));
        assertEquals(APP_HDR_PLAYBACK_MODE_DEFAULT,
                mController.getModeForUid(UID_YOUTUBE));
        assertEquals(APP_HDR_PLAYBACK_MODE_DEFAULT,
                mController.getModeForPackage(PKG_NETFLIX));
        assertEquals(APP_HDR_PLAYBACK_MODE_DEFAULT,
                mController.getModeForUid(UID_NETFLIX));
    }

    @Test
    public void testUninstalledPackagePersistsAndBindsOnInstall() {
        mController.setModeForPackage(PKG_UNINSTALLED, APP_HDR_PLAYBACK_MODE_SDR_ONLY);
        assertEquals(APP_HDR_PLAYBACK_MODE_SDR_ONLY,
                mController.getModeForPackage(PKG_UNINSTALLED));

        final int newlyInstalledUid = 10222;
        mPackageUids.put(PKG_UNINSTALLED, newlyInstalledUid);
        mController.onPackagesChanged(PKG_UNINSTALLED);

        assertEquals(APP_HDR_PLAYBACK_MODE_SDR_ONLY,
                mController.getModeForUid(newlyInstalledUid));
    }

    @Test
    public void testUserSwitchingIsolatesPolicies() {
        mController.setModeForPackage(PKG_YOUTUBE, APP_HDR_PLAYBACK_MODE_SDR_ONLY);
        assertEquals(APP_HDR_PLAYBACK_MODE_SDR_ONLY, mController.getModeForUid(UID_YOUTUBE));

        final int secondaryUser = 10;
        mController.onUserSwitching(secondaryUser);
        assertEquals(APP_HDR_PLAYBACK_MODE_DEFAULT, mController.getModeForUid(UID_YOUTUBE));

        mController.onUserSwitching(UserHandle.USER_SYSTEM);
        assertEquals(APP_HDR_PLAYBACK_MODE_SDR_ONLY, mController.getModeForUid(UID_YOUTUBE));
    }

    @Test
    public void testSerializationAndMalformedParsingResilience() {
        ArrayMap<String, Integer> map = new ArrayMap<>();
        map.put(PKG_YOUTUBE, APP_HDR_PLAYBACK_MODE_SDR_ONLY);
        map.put(PKG_NETFLIX, APP_HDR_PLAYBACK_MODE_HDR_ALLOWED);

        String serialized = AppHdrPolicyController.serializePolicies(map);
        ArrayMap<String, Integer> parsed = AppHdrPolicyController.parsePolicies(serialized);
        assertEquals(2, parsed.size());
        assertEquals(Integer.valueOf(APP_HDR_PLAYBACK_MODE_SDR_ONLY), parsed.get(PKG_YOUTUBE));
        assertEquals(Integer.valueOf(APP_HDR_PLAYBACK_MODE_HDR_ALLOWED), parsed.get(PKG_NETFLIX));

        ArrayMap<String, Integer> malformedParsed = AppHdrPolicyController.parsePolicies(
                ";;=2;invalid_entry;com.foo=99;com.bar=notanumber;com.valid.app=2;com.def.app=0;");
        assertEquals(1, malformedParsed.size());
        assertEquals(Integer.valueOf(APP_HDR_PLAYBACK_MODE_SDR_ONLY),
                malformedParsed.get("com.valid.app"));
    }

    @Test
    public void testModeChangeRestartsOnlyThatPackage() {
        mController.setModeForPackage(PKG_YOUTUBE, APP_HDR_PLAYBACK_MODE_SDR_ONLY);
        assertEquals(Arrays.asList(PKG_YOUTUBE + "/u0"), mStoppedPackages);

        // Re-applying the same mode is a no-op and must not kill the app again.
        mController.setModeForPackage(PKG_YOUTUBE, APP_HDR_PLAYBACK_MODE_SDR_ONLY);
        assertEquals(1, mStoppedPackages.size());

        mController.setModeForPackage(PKG_YOUTUBE, APP_HDR_PLAYBACK_MODE_DEFAULT);
        assertEquals(Arrays.asList(PKG_YOUTUBE + "/u0", PKG_YOUTUBE + "/u0"), mStoppedPackages);
    }

    @Test
    public void testResetAllRestartsEveryOverriddenPackage() {
        mController.setModeForPackage(PKG_YOUTUBE, APP_HDR_PLAYBACK_MODE_SDR_ONLY);
        mController.setModeForPackage(PKG_NETFLIX, APP_HDR_PLAYBACK_MODE_HDR_ALLOWED);
        mStoppedPackages.clear();

        mController.resetAllModes();

        assertEquals(2, mStoppedPackages.size());
        assertTrue(mStoppedPackages.contains(PKG_YOUTUBE + "/u0"));
        assertTrue(mStoppedPackages.contains(PKG_NETFLIX + "/u0"));
    }

    @Test
    public void testSystemAndUninstalledPackagesAreNotStopped() {
        mPackageUids.put("com.android.settings", Process.SYSTEM_UID);
        mController.setModeForPackage("com.android.settings", APP_HDR_PLAYBACK_MODE_SDR_ONLY);
        mController.setModeForPackage(PKG_UNINSTALLED, APP_HDR_PLAYBACK_MODE_SDR_ONLY);

        assertTrue(mStoppedPackages.isEmpty());
    }

    @Test
    public void testSettingSyncDoesNotRestartPackages() {
        mUserPolicies.put(UserHandle.USER_SYSTEM, PKG_YOUTUBE + "=2");
        mController.onSettingChanged();

        assertEquals(APP_HDR_PLAYBACK_MODE_SDR_ONLY, mController.getModeForUid(UID_YOUTUBE));
        assertTrue(mStoppedPackages.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidPackageNameRejected() {
        mController.setModeForPackage("bad;package=name", APP_HDR_PLAYBACK_MODE_SDR_ONLY);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidModeRejected() {
        mController.setModeForPackage(PKG_YOUTUBE, 99);
    }
}
