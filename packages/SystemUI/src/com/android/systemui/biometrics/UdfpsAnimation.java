/**
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.content.Intent.ACTION_USER_SWITCHED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayUtils;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.systemui.Dependency;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

public class UdfpsAnimation extends ImageView {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "UdfpsAnimations";

    private static final String UDFPS_ANIMATIONS_PACKAGE = "com.crdroid.udfps.animations";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** One frame on screen, one being decoded and one the renderer may still be drawing. */
    private static final int FRAME_BUFFERS = 3;

    private boolean mShowing = false;
    private final Context mContext;
    private final int mAnimationSize;

    private final WindowManager.LayoutParams mAnimParams = new WindowManager.LayoutParams();
    private final WindowManager mWindowManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private boolean mIsKeyguard;

    private final KeyguardStateController mKeyguardStateController;
    private final AuthController mAuthController;
    private final FingerprintSensorPropertiesInternal mProps;

    private boolean mAnimationAdded = false;

    private int mStyleIdx = -1;
    private Style mStyle;
    private Bitmap mFirstFrame;

    // The packs have up to 60 frames of up to 1080 px each, too much to keep decoded, so frames
    // are decoded one at a time while the animation plays. Only touched on the decode thread.
    private HandlerThread mDecodeThread;
    private Handler mDecodeHandler;
    private final Bitmap[] mBuffers = new Bitmap[FRAME_BUFFERS];
    private int mNextBuffer;

    private volatile int mGeneration;
    private volatile long mStartTime;

    private final KeyguardStateController.Callback keyguardStateCallback = new KeyguardStateController.Callback() {
        @Override
        public void onKeyguardFadingAwayChanged() {
            removeAnimation();
        }
        @Override
        public void onKeyguardGoingAwayChanged() {
            removeAnimation();
        }
    };

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                updateAnimationStyle();
            }
        }
    };

    public UdfpsAnimation(Context context, WindowManager windowManager,
           FingerprintSensorPropertiesInternal props, AuthController authController) {
        super(context);
        mContext = context;
        mAuthController = authController;
        mProps = props;

        mWindowManager = windowManager;

        mKeyguardStateController = Dependency.get(KeyguardStateController.class);

        mAnimationSize = mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_size);

        mAnimParams.height = mAnimationSize;
        mAnimParams.width = mAnimationSize;

        mAnimParams.format = PixelFormat.TRANSLUCENT;
        mAnimParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY; // it must be behind Udfps icon
        mAnimParams.flags =  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;
        // The position below is in display coordinates; without this the window is laid out
        // under the status bar and the animation lands that far below the sensor.
        mAnimParams.setFitInsetsTypes(0);
        mAnimParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        updatePosition();

        // The frames fill the window, as they did as its background.
        setScaleType(ImageView.ScaleType.FIT_CENTER);
    }

    private void addAnimation() {
        if (!mAnimationAdded) {
            IntentFilter filter = new IntentFilter(ACTION_USER_SWITCHED);
            mContext.registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, filter, null, null);

            mDecodeThread = new HandlerThread("UdfpsAnimation", Process.THREAD_PRIORITY_DISPLAY);
            mDecodeThread.start();
            mDecodeHandler = new Handler(mDecodeThread.getLooper());

            updateAnimationStyle();

            mKeyguardStateController.addCallback(keyguardStateCallback);
            mAnimationAdded = true;
        }
    }

    private void updateAnimationStyle() {
        updateAnimationStyle(Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.UDFPS_ANIM_STYLE, 0, UserHandle.USER_CURRENT));
    }

    private void updateAnimationStyle(int styleIdx) {
        mStyleIdx = styleIdx;
        stopPlayback();
        mFirstFrame = null;
        mStyle = styleIdx != 0 ? loadStyle(styleIdx) : null;
        final Style style = mStyle;
        if (style != null && mDecodeHandler != null) {
            // Ready for the finger, so the animation starts the moment it lands.
            mDecodeHandler.post(() -> {
                final Bitmap first = style.decode(0, null);
                mMainHandler.post(() -> {
                    if (mStyle == style) {
                        mFirstFrame = first;
                    }
                });
            });
        }
    }

    private Style loadStyle(int styleIdx) {
        final Resources res;
        try {
            res = mContext.getPackageManager().getResourcesForApplication(UDFPS_ANIMATIONS_PACKAGE);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Failed to load package resources", e);
            return null;
        }
        final int namesId = res.getIdentifier("udfps_animation_styles", "array",
                UDFPS_ANIMATIONS_PACKAGE);
        if (namesId == 0) {
            return null;
        }
        final String[] styleNames = res.getStringArray(namesId);
        if (styleIdx < 0 || styleIdx >= styleNames.length) {
            return null;
        }
        final String drawableName = styleNames[styleIdx];
        if (DEBUG) Log.i(LOG_TAG, "Updating animation style to:" + drawableName);
        final int resId = res.getIdentifier(drawableName, "drawable", UDFPS_ANIMATIONS_PACKAGE);
        if (resId == 0) {
            return null;
        }
        try {
            return Style.load(res, resId, mAnimationSize);
        } catch (IOException | RuntimeException | org.xmlpull.v1.XmlPullParserException e) {
            Log.w(LOG_TAG, "Could not load animation " + drawableName, e);
            return null;
        }
    }

    public boolean isAnimationEnabled() {
        return mStyle != null;
    }

    private float getDisplayFactor() {
        final DisplayInfo info = new DisplayInfo();
        mContext.getDisplay().getDisplayInfo(info);
        final Display.Mode maxMode = DisplayUtils.getMaximumResolutionDisplayMode(
                info.supportedModes);
        if (maxMode == null) {
            return 1f;
        }
        return DisplayUtils.getPhysicalPixelDisplaySizeRatio(
                maxMode.getPhysicalWidth(), maxMode.getPhysicalHeight(),
                info.getNaturalWidth(), info.getNaturalHeight());
    }

    public void updatePosition() {
        Point displaySize = new Point();
        mWindowManager.getDefaultDisplay().getRealSize(displaySize);
        boolean isFullResolution = displaySize.y > 3000;
        Point udfpsLocation = mAuthController.getUdfpsLocation();
        float scaleFactor = getDisplayFactor();
        float udfpsRadius = isFullResolution ? mAuthController.getUdfpsRadius() : mProps.getLocation().sensorRadius;
        float udfpsLocationY = isFullResolution && udfpsLocation != null ? udfpsLocation.y : mProps.getLocation().sensorLocationY;
        int animationOffset = (int) (mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_offset) * scaleFactor);
        // Centre the animation on the sensor.
        mAnimParams.y = (int) (udfpsLocationY * scaleFactor) - (mAnimationSize / 2)
                + animationOffset;
        if (DEBUG) {
            Log.d(LOG_TAG, "updatePosition: displaySize=" + displaySize +
                           ", isFullResolution=" + isFullResolution +
                           ", udfpsLocation=" + udfpsLocation +
                           ", udfpsRadius=" + udfpsRadius +
                           ", scaleFactor=" + scaleFactor +
                           ", udfpsLocationY=" + udfpsLocationY +
                           ", animationOffset=" + animationOffset +
                           ", mAnimParams.y=" + mAnimParams.y);
        }
    }

    public void show() {
        // Pick up a style chosen in settings since the animation was set up.
        final int styleIdx = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.UDFPS_ANIM_STYLE, 0, UserHandle.USER_CURRENT);
        if (styleIdx != mStyleIdx) {
            updateAnimationStyle(styleIdx);
        }
        if (!mIsKeyguard || mStyle == null) {
            return;
        }

        if (mShowing && !isAttachedToWindow()) {
            mShowing = false;
        }

        if (mShowing && isAttachedToWindow()) {
            return;
        }

        try {
            startPlayback();
            if (!isAttachedToWindow()) {
                mWindowManager.addView(this, mAnimParams);
            } else {
                mWindowManager.updateViewLayout(this, mAnimParams);
            }

            mShowing = true;
        } catch (RuntimeException e) {
            mShowing = false;
            stopPlayback();
            Log.e(LOG_TAG, "Error adding animation window", e);
        }
    }

    public void hide() {
        stopPlayback();
        try {
            if (isAttachedToWindow()) {
                mWindowManager.removeViewImmediate(this);
            }
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "Error removing animation window", e);
        }

        mShowing = false;
    }

    private void startPlayback() {
        final Style style = mStyle;
        // Kept here: removeAnimation() clears the field while a step may still be decoding.
        final Handler handler = mDecodeHandler;
        if (style == null || handler == null) {
            return;
        }
        final int generation = ++mGeneration;
        mStartTime = SystemClock.uptimeMillis();
        setImageBitmap(mFirstFrame);
        final int shownFrame = mFirstFrame != null ? 0 : -1;
        handler.post(() -> step(handler, style, generation, shownFrame));
    }

    /** Stops the frames and lets the decode buffers go; only the first frame stays decoded. */
    private void stopPlayback() {
        mGeneration++;
        setImageDrawable(null);
        if (mDecodeHandler != null) {
            mDecodeHandler.post(() -> Arrays.fill(mBuffers, null));
        }
    }

    /** Decodes the frame due now and schedules the next one. Runs on the decode thread. */
    private void step(Handler handler, Style style, int generation, int shownFrame) {
        if (generation != mGeneration) {
            return;
        }
        final long elapsed = SystemClock.uptimeMillis() - mStartTime;
        final int frame = style.frameAt(elapsed);
        if (frame != shownFrame) {
            final Bitmap bitmap = style.decode(frame, mBuffers[mNextBuffer]);
            if (bitmap == null || generation != mGeneration) {
                return;
            }
            mBuffers[mNextBuffer] = bitmap;
            mNextBuffer = (mNextBuffer + 1) % FRAME_BUFFERS;
            mMainHandler.post(() -> {
                if (generation == mGeneration) {
                    setImageBitmap(bitmap);
                }
            });
        }
        final long next = style.nextFrameTime(elapsed);
        if (next >= 0) {
            handler.postAtTime(() -> step(handler, style, generation, frame), mStartTime + next);
        }
    }

    public void removeAnimation() {
        hide();
        if (mAnimationAdded) {
            mContext.unregisterReceiver(mIntentReceiver);
            mKeyguardStateController.removeCallback(keyguardStateCallback);
            mDecodeThread.quitSafely();
            mDecodeThread = null;
            mDecodeHandler = null;
            mAnimationAdded = false;
        }
        mIsKeyguard = false;
        mStyle = null;
        mStyleIdx = -1;
        mFirstFrame = null;
    }

    public void setIsKeyguard(boolean isKeyguard) {
        mIsKeyguard = isKeyguard;
        if (mIsKeyguard) {
            addAnimation();
        } else {
            removeAnimation();
        }
    }

    public boolean isKeyguard() {
        return mIsKeyguard;
    }

    /** The frames and timing of an animation-list, decoded at no more than the window size. */
    private static final class Style {
        private final Resources mRes;
        private final int[] mFrameIds;
        private final int[] mFrameStarts;
        private final int mDuration;
        private final boolean mOneShot;
        private final int mSourceSize;
        private final int mTargetSize;

        private Style(Resources res, int[] frameIds, int[] frameStarts, int duration,
                boolean oneShot, int sourceSize, int targetSize) {
            mRes = res;
            mFrameIds = frameIds;
            mFrameStarts = frameStarts;
            mDuration = duration;
            mOneShot = oneShot;
            mSourceSize = sourceSize;
            mTargetSize = targetSize;
        }

        static Style load(Resources res, int resId, int maxSize)
                throws IOException, org.xmlpull.v1.XmlPullParserException {
            final ArrayList<Integer> ids = new ArrayList<>();
            final ArrayList<Integer> durations = new ArrayList<>();
            boolean oneShot = false;
            try (XmlResourceParser parser = res.getXml(resId)) {
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG) {
                        continue;
                    }
                    if ("animation-list".equals(parser.getName())) {
                        oneShot = parser.getAttributeBooleanValue(ANDROID_NS, "oneshot", false);
                    } else if ("item".equals(parser.getName())) {
                        final int id = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0);
                        if (id != 0) {
                            ids.add(id);
                            durations.add(Math.max(1,
                                    parser.getAttributeIntValue(ANDROID_NS, "duration", 0)));
                        }
                    }
                }
            }
            if (ids.isEmpty()) {
                return null;
            }
            final int[] frameIds = new int[ids.size()];
            final int[] frameStarts = new int[ids.size()];
            int time = 0;
            for (int i = 0; i < frameIds.length; i++) {
                frameIds[i] = ids.get(i);
                frameStarts[i] = time;
                time += durations.get(i);
            }

            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = res.openRawResource(frameIds[0])) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            final int sourceSize = Math.max(bounds.outWidth, bounds.outHeight);
            if (sourceSize <= 0) {
                return null;
            }
            return new Style(res, frameIds, frameStarts, time, oneShot, sourceSize,
                    Math.min(sourceSize, maxSize));
        }

        /** The frame to show {@code elapsed} ms into the animation. */
        int frameAt(long elapsed) {
            if (mOneShot && elapsed >= mDuration) {
                return mFrameIds.length - 1;
            }
            final int time = (int) (elapsed % mDuration);
            final int index = Arrays.binarySearch(mFrameStarts, time);
            return index >= 0 ? index : -index - 2;
        }

        /** When the frame after the one at {@code elapsed} is due, or -1 at the end. */
        long nextFrameTime(long elapsed) {
            if (mOneShot && elapsed >= mDuration) {
                return -1;
            }
            final int frame = frameAt(elapsed);
            final long loopStart = elapsed - elapsed % mDuration;
            return loopStart + (frame + 1 < mFrameStarts.length
                    ? mFrameStarts[frame + 1] : mDuration);
        }

        /** Decodes a frame, scaled to the window, reusing {@code reuse} when it can. */
        Bitmap decode(int frame, Bitmap reuse) {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            // Raw streams skip the density scaling the mdpi pack would get as a resource.
            options.inScaled = mTargetSize != mSourceSize;
            options.inDensity = mSourceSize;
            options.inTargetDensity = mTargetSize;
            options.inMutable = true;
            options.inBitmap = reuse;
            try {
                return decodeFrame(frame, options);
            } catch (IllegalArgumentException e) {
                options.inBitmap = null;
                return decodeFrame(frame, options);
            }
        }

        private Bitmap decodeFrame(int frame, BitmapFactory.Options options) {
            try (InputStream in = mRes.openRawResource(mFrameIds[frame])) {
                final Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
                if (bitmap != null) {
                    bitmap.setDensity(Bitmap.DENSITY_NONE);
                }
                return bitmap;
            } catch (IOException | Resources.NotFoundException e) {
                Log.w(LOG_TAG, "Could not decode animation frame " + frame, e);
                return null;
            }
        }
    }
}
