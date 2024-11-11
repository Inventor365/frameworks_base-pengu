/*
 * SPDX-FileCopyrightText: 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.crdroid;

import android.content.Context;
import android.content.pm.PackageManager;

/** Helpers used by the UDFPS icon and animation code. */
public final class Utils {

    private Utils() {
    }

    /** Whether {@code packageName} is installed for the calling user. */
    public static boolean isPackageInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName,
                    PackageManager.PackageInfoFlags.of(0));
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
