/*
 * Copyright (C) 2024-2025 crDroid Android Project
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

package com.android.systemui.biometrics;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import java.io.IOException;

/**
 * Abstract base class for drawable displayed when the finger is not touching the
 * sensor area.
 */
public abstract class UdfpsIconDrawable extends Drawable {
    private static final String TAG = "UdfpsIconDrawable";

    /**
     * The pack's bitmaps are up to 1773 px and live in the mdpi folder, so loading them as
     * resources scales them up again by the density. The icon covers the sensor, so this is
     * plenty.
     */
    private static final float MAX_ICON_SIZE_DP = 128f;

    @NonNull private final Context mContext;
    private final int mMaxSizePx;
    private Drawable mUdfpsDrawable;
    private Resources mPackRes;
    private String[] mUdfpsIcons;
    private int mSelectedIcon = -1;
    private int mAlpha = 255;
    private float mAlphaScale = 1f;
    private boolean mListening;

    private final ContentObserver mContentObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            reload();
        }
    };

    public UdfpsIconDrawable(@NonNull Context context) {
        mContext = context;
        mMaxSizePx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                MAX_ICON_SIZE_DP, context.getResources().getDisplayMetrics()));
        if (UdfpsIconPack.isInstalled(context)) {
            try {
                mPackRes = context.getPackageManager()
                        .getResourcesForApplication(UdfpsIconPack.PACKAGE);
                int res = mPackRes.getIdentifier("udfps_icons", "array", UdfpsIconPack.PACKAGE);
                mUdfpsIcons = res != 0 ? mPackRes.getStringArray(res) : null;
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "UDFPS package not found", e);
            }
        }
        reload();
    }

    /** Follows the chosen icon while the view that shows it is attached. */
    public void setListening(boolean listening) {
        if (mListening == listening || mUdfpsIcons == null) {
            return;
        }
        mListening = listening;
        if (listening) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.UDFPS_ICON), false,
                    mContentObserver, UserHandle.USER_ALL);
            reload();
        } else {
            mContext.getContentResolver().unregisterContentObserver(mContentObserver);
        }
    }

    public void destroy() {
        setListening(false);
    }

    /** Scales the alpha the icon is drawn with, for a dimmer icon on the dozing screen. */
    public void setAlphaScale(float alphaScale) {
        mAlphaScale = alphaScale;
        applyAlpha();
    }

    private void reload() {
        int selected = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.UDFPS_ICON, 0, UserHandle.USER_CURRENT);
        if (selected == mSelectedIcon) {
            return;
        }
        mSelectedIcon = selected;
        mUdfpsDrawable = mUdfpsIcons != null && selected > 0 && selected < mUdfpsIcons.length
                ? loadDrawable(mUdfpsIcons[selected]) : null;
        if (mUdfpsDrawable != null) {
            mUdfpsDrawable.setBounds(getBounds());
            applyAlpha();
        }
        invalidateSelf();
    }

    @Nullable
    private Drawable loadDrawable(String resName) {
        int resId = mPackRes.getIdentifier(resName, "drawable", UdfpsIconPack.PACKAGE);
        if (resId == 0) {
            return null;
        }
        TypedValue value = new TypedValue();
        mPackRes.getValue(resId, value, true);
        if (value.string != null && value.string.toString().endsWith(".xml")) {
            return ResourcesCompat.getDrawable(mPackRes, resId, null);
        }
        try {
            Bitmap bitmap = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(mPackRes, resId), (decoder, info, source) -> {
                        // Small enough for software, which any canvas can draw.
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        Size size = info.getSize();
                        float scale = Math.min(1f, (float) mMaxSizePx
                                / Math.max(size.getWidth(), size.getHeight()));
                        decoder.setTargetSize(
                                Math.max(1, Math.round(size.getWidth() * scale)),
                                Math.max(1, Math.round(size.getHeight() * scale)));
                    });
            return new BitmapDrawable(mContext.getResources(), bitmap);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not load UDFPS icon " + resName, e);
            return null;
        }
    }

    private void applyAlpha() {
        if (mUdfpsDrawable != null) {
            mUdfpsDrawable.setAlpha(Math.round(mAlpha * mAlphaScale));
        }
        invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        applyAlpha();
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    Drawable getUdfpsDrawable() {
        return mUdfpsDrawable;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        if (mUdfpsDrawable != null) {
            mUdfpsDrawable.setBounds(bounds);
        }
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // Do not set color filter
    }

    @Override
    public int getOpacity() {
        return 0;
    }
}
