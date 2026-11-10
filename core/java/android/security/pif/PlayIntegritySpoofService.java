package android.security.pif;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IActivityManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Base64;
import android.util.JsonReader;
import android.util.Log;

import com.android.internal.util.custom.PixelDeviceRepository;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** @hide */
public final class PlayIntegritySpoofService {
    private static final String TAG = "PIF";

    private static final long LOAD_RETRY_INTERVAL_MS = 5000;

    private static final String DROIDGUARD_PACKAGE = "com.google.android.gms.unstable";
    private static final String VENDING_PACKAGE = "com.android.vending";
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String GPHOTOS_PACKAGE = "com.google.android.apps.photos";

    private static final Map<String, Object> PIXEL_XL_PROPS = Map.of(
        "BRAND", "google",
        "MANUFACTURER", "Google",
        "DEVICE", "marlin",
        "PRODUCT", "marlin",
        "HARDWARE", "marlin",
        "ID", "QP1A.191005.007.A3",
        "MODEL", "Pixel XL",
        "FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys"
    );

    private static final ArraySet<String> PRIV_PKGS = new ArraySet<>();
    private static final ArraySet<String> FEATURES_PIXEL = new ArraySet<>();
    private static final ArraySet<String> FEATURES_PIXEL_OTHERS = new ArraySet<>();
    private static final ArraySet<String> FEATURES_TENSOR = new ArraySet<>();
    private static final ArraySet<String> FEATURES_NEXUS = new ArraySet<>();

    static {
        Collections.addAll(FEATURES_PIXEL,
                "com.google.android.apps.photos.PIXEL_2019_PRELOAD",
                "com.google.android.apps.photos.PIXEL_2019_MIDYEAR_PRELOAD",
                "com.google.android.apps.photos.PIXEL_2018_PRELOAD",
                "com.google.android.apps.photos.PIXEL_2017_PRELOAD",
                "com.google.android.feature.PIXEL_2021_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2020_EXPERIENCE",
                "com.google.android.feature.PIXEL_2020_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2019_EXPERIENCE",
                "com.google.android.feature.PIXEL_2019_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2018_EXPERIENCE",
                "com.google.android.feature.PIXEL_2017_EXPERIENCE",
                "com.google.android.feature.PIXEL_EXPERIENCE",
                "com.google.android.feature.GOOGLE_BUILD",
                "com.google.android.feature.GOOGLE_EXPERIENCE"
        );

        Collections.addAll(FEATURES_PIXEL_OTHERS,
                "com.google.android.feature.ASI",
                "com.google.android.feature.ANDROID_ONE_EXPERIENCE",
                "com.google.android.feature.GOOGLE_FI_BUNDLED",
                "com.google.android.feature.LILY_EXPERIENCE",
                "com.google.android.feature.TURBO_PRELOAD",
                "com.google.android.feature.WELLBEING",
                "com.google.lens.feature.IMAGE_INTEGRATION",
                "com.google.lens.feature.CAMERA_INTEGRATION",
                "com.google.photos.trust_debug_certs",
                "com.google.android.feature.AER_OPTIMIZED",
                "com.google.android.feature.NEXT_GENERATION_ASSISTANT",
                "android.software.game_service",
                "com.google.android.feature.EXCHANGE_6_2",
                "com.google.android.apps.dialer.call_recording_audio",
                "com.google.android.apps.dialer.SUPPORTED"
        );

        Collections.addAll(FEATURES_TENSOR,
                "com.google.android.feature.PIXEL_2026_EXPERIENCE",
                "com.google.android.feature.PIXEL_2026_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2025_EXPERIENCE",
                "com.google.android.feature.PIXEL_2025_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2024_EXPERIENCE",
                "com.google.android.feature.PIXEL_2024_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2023_EXPERIENCE",
                "com.google.android.feature.PIXEL_2023_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2022_EXPERIENCE",
                "com.google.android.feature.PIXEL_2022_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2021_EXPERIENCE"
        );

        Collections.addAll(FEATURES_NEXUS,
                "com.google.android.apps.photos.NEXUS_PRELOAD",
                "com.google.android.apps.photos.nexus_preload",
                "com.google.android.feature.PIXEL_EXPERIENCE",
                "com.google.android.feature.GOOGLE_BUILD",
                "com.google.android.feature.GOOGLE_EXPERIENCE"
        );

        Collections.addAll(PRIV_PKGS,
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.photos",
                "com.google.android.apps.pixel.agent",
                "com.google.android.apps.pixel.creativeassistant"
        );
    }

    private static final String ROM_SIGNATURE_DATA = "MIIFyTCCA7GgAwIBAgIVALyxxl+zDS9SL68SzOr48309eAZyMA0GCSqGSIb3DQEBCwUAMHQxCzAJ" +
            "BgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQw" +
            "EgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDAg" +
            "Fw0yMjExMDExODExMzVaGA8yMDUyMTEwMTE4MTEzNVowdDELMAkGA1UEBhMCVVMxEzARBgNVBAgT" +
            "CkNhbGlmb3JuaWExFjAUBgNVBAcTDU1vdW50YWluIFZpZXcxFDASBgNVBAoTC0dvb2dsZSBJbmMu" +
            "MRAwDgYDVQQLEwdBbmRyb2lkMRAwDgYDVQQDEwdBbmRyb2lkMIICIjANBgkqhkiG9w0BAQEFAAOC" +
            "Ag8AMIICCgKCAgEAsqtalIy/nctKlrhd1UVoDffFGnDf9GLi0QQhsVoJkfF16vDDydZJOycG7/kQ" +
            "ziRZhFdcoMrIYZzzw0ppBjsSe1AiWMuKXwTBaEtxN99S1xsJiW4/QMI6N6kMunydWRMsbJ6aAxi1" +
            "lVq0bxSwr8Sg/8u9HGVivfdG8OpUM+qjuV5gey5xttNLK3BZDrAlco8RkJZryAD40flmJZrWXJmc" +
            "r2HhJJUnqG4Z3MSziEgW1u1JnnY3f/BFdgYsA54SgdUGdQP3aqzSjIpGK01/vjrXvifHazSANjvl" +
            "0AUE5i6AarMw2biEKB2ySUDp8idC5w12GpqDrhZ/QkW8yBSa87KbkMYXuRA2Gq1fYbQx3YJraw0U" +
            "gZ4M3fFKpt6raxxM5j0sWHlULD7dAZMERvNESVrKG3tQ7B39WAD8QLGYc45DFEGOhKv5Fv8510h5" +
            "sXK502IvGpI4FDwz2rbtAgJ0j+16db5wCSW5ThvNPhCheyciajc8dU1B5tJzZN/ksBpzne4Xf9gO" +
            "LZ9ZU0+3Z5gHVvTS/YpxBFwiFpmL7dvGxew0cXGSsG5UTBlgr7i0SX0WhY4Djjo8IfPwrvvA0QaC" +
            "FamdYXKqBsSHgEyXS9zgGIFPt2jWdhaS+sAa//5SXcWro0OdiKPuwEzLgj759ke1sHRnvO735dYn" +
            "5whVbzlGyLBh3L0CAwEAAaNQME4wDAYDVR0TBAUwAwEB/zAdBgNVHQ4EFgQUU1eXQ7NoYKjvOQlh" +
            "5V8jHQMoxA8wHwYDVR0jBBgwFoAUU1eXQ7NoYKjvOQlh5V8jHQMoxA8wDQYJKoZIhvcNAQELBQAD" +
            "ggIBAHFIazRLs3itnZKllPnboSd6sHbzeJURKehx8GJPvIC+xWlwWyFO5+GHmgc3yh/SVd3Xja/k" +
            "8Ud59WEYTjyJJWTw0Jygx37rHW7VGn2HDuy/x0D+els+S8HeLD1toPFMepjIXJn7nHLhtmzTPlDW" +
            "DrhiaYsls/k5Izf89xYnI4euuOY2+1gsweJqFGfbznqyqy8xLyzoZ6bvBJtgeY+G3i/9Be14HseS" +
            "Na4FvI1Oze/l2gUu1IXzN6DGWR/lxEyt+TncJfBGKbjafYrfSh3zsE4N3TU7BeOL5INirOMjre/j" +
            "VgB1YQG5qLVaPoz6mdn75AbBBm5a5ahApLiKqzy/hP+1rWgw8Ikb7vbUqov/bnY3IlIU6XcPJTCD" +
            "b9aRZQkStvYpQd82XTyxD/T0GgRLnUj5Uv6iZlikFx1KNj0YNS2T3gyvL++J9B0Y6gAkiG0EtNpl" +
            "z7Pomsv5pVdmHVdKMjqWw5/6zYzVmu5cXFtR384Ti1qwML1xkD6TC3VIv88rKIEjrkY2c+v1frh9" +
            "fRJ2OmzXmML9NgHTjEiJR2Ib2iNrMKxkuTIs9oxKZgrJtJKvdU9qJJKM5PnZuNuHhGs6A/9gt9Oc" +
            "cetYeQvVSqeEmQluWfcunQn9C9Vwi2BJIiVJh4IdWZf5/e2PlSSQ9CJjz2bKI17pzdxOmjQfE0JS" +
            "F7Xt";

    private static PlayIntegritySpoofService sInstance;

    private static final Object sUnsafeLock = new Object();
    private static boolean sUnsafeLookedUp;
    private static Object sUnsafe;
    private static Method sPutObject;
    private static Method sPutInt;
    private static Method sPutLong;
    private static Method sPutBoolean;
    private static Field sFieldOffset;
    private static Method sFieldGetOffset;

    private volatile int mVerboseLogs = 0;
    private volatile boolean mSpoofBuild = true;
    // Off unless the config asks for it. Upstream defaults this to on, but it makes
    // DroidGuard's getCertificateChain() throw, which would bypass the keybox
    // attestation done in AndroidKeyStoreSpi for a device that has one.
    private volatile boolean mSpoofProvider = false;
    private volatile boolean mSpoofSignature = false;
    // Replaces the old all-or-nothing "spoofVendingBuild" boolean. This now matches
    // upstream PlayIntegrityFork semantics: "0"/empty = disabled (default), "1"/"true" =
    // spoof the configured FINGERPRINT field only, or any other value = use that literal
    // string as a custom FINGERPRINT to serve to Play Store instead of the DroidGuard one.
    private volatile String mSpoofVendingFinger = "0";
    // 0 = disabled, 1 = spoof Play Store's SDK_INT to 32, N > 1 = spoof it to N.
    // Never raises SDK_INT above the real one.
    private volatile int mSpoofVendingSdk = 0;
    private volatile boolean mSpoofPhotos = false;
    // Set once spoofProvider() has armed the DroidGuard certificate-chain block in
    // this process. Static so AndroidKeyStoreSpi can check it without an instance.
    private static volatile boolean sBlockCertificateChain = false;
    private volatile boolean mDebug = false;

    private final Map<String, String> mBuildFields = new ConcurrentHashMap<>();

    private volatile boolean mConfigLoaded = false;
    private volatile boolean mSignatureSpoofed = false;
    private volatile boolean mLoadFailureLogged = false;
    private volatile long mLastLoadAttempt = 0;

    private PlayIntegritySpoofService() {}

    public static synchronized PlayIntegritySpoofService getInstance() {
        if (sInstance == null) {
            sInstance = new PlayIntegritySpoofService();
            sInstance.ensureLoaded();
        }
        return sInstance;
    }

    /**
     * Retries loadConfig() when the config isn't loaded yet, at most once per
     * {@link #LOAD_RETRY_INTERVAL_MS}. A single attempt in getInstance() can run
     * before ActivityManager or Settings.Secure are ready, which would otherwise
     * leave the process unspoofed for its whole lifetime. In system_server it
     * waits for boot to complete first.
     */
    private void ensureLoaded() {
        if (mConfigLoaded || !canLoadNow()) return;
        synchronized (this) {
            if (mConfigLoaded) return;
            final long now = SystemClock.uptimeMillis();
            if (mLastLoadAttempt != 0 && now - mLastLoadAttempt < LOAD_RETRY_INTERVAL_MS) {
                return;
            }
            loadConfig();
        }
    }

    private static boolean canLoadNow() {
        return !ActivityThread.isSystem()
                || "1".equals(SystemProperties.get("sys.boot_completed"));
    }

    public synchronized void loadConfig() {
        mLastLoadAttempt = SystemClock.uptimeMillis();
        mBuildFields.clear();
        mConfigLoaded = false;

        IActivityManager am = ActivityManager.getService();
        if (am == null) {
            if (!mLoadFailureLogged) {
                mLoadFailureLogged = true;
                Log.w(TAG, "ActivityManager not ready, skipping PIF config load");
            }
            return;
        }

        String content;
        try {
            content = am.getSpoofPifConfig();
            String spoofPhotos = am.getSpoofPifSpoofPhotos();
            mSpoofPhotos = spoofPhotos == null || "1".equals(spoofPhotos)
                    || "true".equalsIgnoreCase(spoofPhotos);
        } catch (Throwable e) {
            if (!mLoadFailureLogged) {
                mLoadFailureLogged = true;
                Log.w(TAG, "PIF config not available yet: " + e);
            }
            return;
        }

        if (content == null || content.isEmpty()) {
            if (!mLoadFailureLogged) {
                mLoadFailureLogged = true;
                Log.w(TAG, "No PIF config in Settings.Secure");
            }
            return;
        }

        mVerboseLogs = 0;
        mSpoofBuild = true;
        mSpoofProvider = false;
        mSpoofSignature = false;
        mSpoofVendingFinger = "0";
        mSpoofVendingSdk = 0;
        mDebug = false;

        try {
            String trimmed = content.trim();
            if (trimmed.startsWith("{")) {
                parseJson(content);
            } else {
                parseProp(content);
            }

            deriveFieldsFromFingerprint();
            deriveSecurityPatchAndInitialSdk();

            mLoadFailureLogged = false;
            mConfigLoaded = true;
            Log.i(TAG, "PIF config loaded, fields=" + mBuildFields.size());
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load PIF config", e);
        }
    }

    /**
     * Fills BRAND / PRODUCT / DEVICE / ID / INCREMENTAL / TYPE / TAGS / RELEASE from
     * FINGERPRINT when the config doesn't set them, like PIF's migrate.sh does.
     * Without this DroidGuard sees the spoofed FINGERPRINT next to the ROM's own
     * Build fields, which is easy to cross-check.
     * Format: brand/product/device:release/id/incremental:type/tags
     */
    private void deriveFieldsFromFingerprint() {
        String fp = mBuildFields.get("FINGERPRINT");
        if (fp == null) return;
        int colon = fp.indexOf(':');
        if (colon < 0) return;
        String[] head = fp.substring(0, colon).split("/");
        String[] rest = fp.substring(colon + 1).split("/");
        if (rest.length < 4) return;
        String[] incrementalAndType = rest[2].split(":");
        if (incrementalAndType.length != 2) return;
        if (head.length == 3) {
            mBuildFields.putIfAbsent("BRAND", head[0]);
            mBuildFields.putIfAbsent("PRODUCT", head[1]);
            mBuildFields.putIfAbsent("DEVICE", head[2]);
        }
        // A Canary fingerprint says CANARY here, which isn't a release; keep a numeric one.
        final String release = PixelDeviceRepository.releaseFromFingerprint(fp);
        if (release != null) mBuildFields.putIfAbsent("RELEASE", release);
        mBuildFields.putIfAbsent("ID", rest[1]);
        mBuildFields.putIfAbsent("INCREMENTAL", incrementalAndType[0]);
        mBuildFields.putIfAbsent("TYPE", incrementalAndType[1]);
        mBuildFields.putIfAbsent("TAGS", rest[3]);
        if (mBuildFields.containsKey("BRAND")) {
            mBuildFields.putIfAbsent("BRAND_FOR_ATTESTATION", mBuildFields.get("BRAND"));
        }
        if (mBuildFields.containsKey("DEVICE")) {
            mBuildFields.putIfAbsent("DEVICE_FOR_ATTESTATION", mBuildFields.get("DEVICE"));
        }
        if (mBuildFields.containsKey("PRODUCT")) {
            mBuildFields.putIfAbsent("PRODUCT_FOR_ATTESTATION", mBuildFields.get("PRODUCT"));
        }
        if (mBuildFields.containsKey("MANUFACTURER")) {
            mBuildFields.putIfAbsent("MANUFACTURER_FOR_ATTESTATION",
                    mBuildFields.get("MANUFACTURER"));
        }
        if (mBuildFields.containsKey("MODEL")) {
            mBuildFields.putIfAbsent("MODEL_FOR_ATTESTATION", mBuildFields.get("MODEL"));
        }
    }

    /**
     * Keeps SECURITY_PATCH and DEVICE_INITIAL_SDK_INT in step with the spoofed
     * profile, matching what AxSpoofManager stages for bionic. A missing patch is
     * taken from the build ID, and a known Pixel codename always gets its own launch
     * SDK rather than a stale configured one.
     */
    private void deriveSecurityPatchAndInitialSdk() {
        if (TextUtils.isEmpty(mBuildFields.get("SECURITY_PATCH"))) {
            final String patch = PixelDeviceRepository.securityPatchFromBuildId(
                    mBuildFields.get("ID"));
            if (patch != null) mBuildFields.put("SECURITY_PATCH", patch);
        }
        final String sdk = PixelDeviceRepository.resolveInitialSdkInt(
                mBuildFields.get("DEVICE"), mBuildFields.get("DEVICE_INITIAL_SDK_INT"));
        if (sdk != null) mBuildFields.put("DEVICE_INITIAL_SDK_INT", sdk);
    }

    private void parseProp(String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int eqIdx = line.indexOf('=');
            if (eqIdx <= 0) continue;

            String key = line.substring(0, eqIdx).trim();
            String value = line.substring(eqIdx + 1).trim();

            int commentIdx = value.indexOf('#');
            if (commentIdx >= 0) {
                value = value.substring(0, commentIdx).trim();
            }

            if (value.isEmpty()) continue;

            processKeyValue(key, value);
        }
    }

    private void parseJson(String content) {
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                String value;
                switch (reader.peek()) {
                    case BOOLEAN:
                        value = String.valueOf(reader.nextBoolean());
                        break;
                    case NUMBER:
                        value = reader.nextString();
                        break;
                    case NULL:
                        reader.nextNull();
                        continue;
                    case BEGIN_OBJECT:
                    case BEGIN_ARRAY:
                        if (mVerboseLogs > 0) Log.d(TAG, "Skipping nested value for " + key);
                        reader.skipValue();
                        continue;
                    default:
                        value = reader.nextString();
                        break;
                }
                processKeyValue(key, value);
            }
            reader.endObject();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse JSON config", e);
        }
    }

    private void processKeyValue(String key, String value) {
        switch (key) {
            case "verboseLogs":
            case "VERBOSE_LOGS":
                try {
                    mVerboseLogs = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    mVerboseLogs = 0;
                }
                break;
            case "spoofBuild":
                mSpoofBuild = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            case "spoofProps":
                // Read by AxSpoofManagerService, which stages the prop overrides for bionic.
                break;
            case "spoofProvider":
                mSpoofProvider = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            case "spoofSignature":
                mSpoofSignature = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            case "spoofVendingFinger":
                mSpoofVendingFinger = value;
                break;
            case "spoofVendingBuild":
                if ("0".equals(mSpoofVendingFinger)
                        && ("1".equals(value) || "true".equalsIgnoreCase(value))) {
                    Log.w(TAG, "spoofVendingBuild is deprecated, treating as spoofVendingFinger=1");
                    mSpoofVendingFinger = "1";
                }
                break;
            case "spoofVendingSdk":
                mSpoofVendingSdk = parseVendingSdk(value);
                break;
            case "DEBUG":
                mDebug = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            default:
                // "*.build.id" style keys are system prop overrides. AxSpoofManagerService
                // stages those for bionic, they are not Build fields.
                if (!key.contains(".") && !key.startsWith("*")) {
                    mBuildFields.put(key, value);
                }
                break;
        }
    }

    private static int parseVendingSdk(String value) {
        if ("true".equalsIgnoreCase(value)) return 1;
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean isTargetProcess(String processName) {
        return DROIDGUARD_PACKAGE.equals(processName) || VENDING_PACKAGE.equals(processName);
    }

    public static boolean isPhotosPackage(String packageName) {
        return TextUtils.equals(GPHOTOS_PACKAGE, packageName);
    }

    public boolean shouldSpoof(String processName) {
        if (!isTargetProcess(processName)) {
            return false;
        }
        ensureLoaded();
        return mConfigLoaded;
    }

    public boolean isGmsProcess(String dataDir) {
        if (dataDir == null) return false;
        return dataDir.endsWith("/" + GMS_PACKAGE) || dataDir.endsWith("/" + VENDING_PACKAGE);
    }

    public boolean isDroidGuard(String processName) {
        return DROIDGUARD_PACKAGE.equals(processName);
    }

    public boolean isVending(String processName) {
        return VENDING_PACKAGE.equals(processName);
    }

    public void spoofBuildFields(String processName) {
        boolean isVending = isVending(processName);
        boolean isDroidGuard = isDroidGuard(processName);

        if (!isDroidGuard && !isVending) return;

        ensureLoaded();
        if (!mConfigLoaded) return;

        if (isVending) {
            // Like upstream, spoofing the SDK level replaces the FINGERPRINT spoof
            // for Play Store rather than adding to it.
            if (mSpoofVendingSdk > 0) {
                spoofSdkInt();
                return;
            }
            String vendingFingerprint = resolveVendingFingerprint();
            if (vendingFingerprint == null) {
                if (mVerboseLogs > 0) Log.d(TAG, "Vending FINGERPRINT spoofing disabled");
                return;
            }
            spoofField("FINGERPRINT", vendingFingerprint, "PS");
            return;
        }

        if (!mSpoofBuild) {
            if (mVerboseLogs > 0) Log.d(TAG, "Build spoofing disabled");
            return;
        }

        for (Map.Entry<String, String> entry : mBuildFields.entrySet()) {
            spoofField(entry.getKey(), entry.getValue(), "DG");
        }
    }

    public void reSpoofBuildFields() {
        if (!mConfigLoaded || !mSpoofBuild) return;
        for (Map.Entry<String, String> entry : mBuildFields.entrySet()) {
            spoofField(entry.getKey(), entry.getValue(), "DG");
        }
    }

    public void spoofProperties() {
        // Property overrides for DroidGuard are staged into persist.sys.pif.* by
        // AxSpoofManagerService and served natively by bionic's custom_rom_hide.
    }

    private String resolveVendingFingerprint() {
        String setting = mSpoofVendingFinger;
        if (setting == null || setting.isEmpty()
                || "0".equals(setting) || "false".equalsIgnoreCase(setting)) {
            return null;
        }
        if ("1".equals(setting) || "true".equalsIgnoreCase(setting)) {
            return mBuildFields.get("FINGERPRINT");
        }
        return setting;
    }

    public void spoofSignature() {
        spoofSignature(DROIDGUARD_PACKAGE);
    }

    public void spoofSignature(String processName) {
        if (isVending(processName)) {
            if (mVerboseLogs > 0) Log.d(TAG, "Signature spoofing skipped for Vending");
            return;
        }
        if (!mSpoofSignature || mSignatureSpoofed) return;

        Signature spoofedSignature = new Signature(Base64.decode(ROM_SIGNATURE_DATA, Base64.DEFAULT));
        Parcelable.Creator<PackageInfo> originalCreator = PackageInfo.CREATOR;
        Parcelable.Creator<PackageInfo> customCreator = new CustomPackageInfoCreator(originalCreator, spoofedSignature);

        Field creatorField;
        try {
            creatorField = findField(PackageInfo.class, "CREATOR");
        } catch (Exception e) {
            Log.e(TAG, "Couldn't find PackageInfo.CREATOR: " + e);
            return;
        }
        try {
            creatorField.setAccessible(true);
            setStaticField(creatorField, customCreator);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't replace PackageInfoCreator: " + e);
            return;
        } finally {
            creatorField.setAccessible(false);
        }

        try {
            Field cacheField = findField(PackageManager.class, "sPackageInfoCache");
            cacheField.setAccessible(true);
            try {
                Object cache = cacheField.get(null);
                if (cache != null) {
                    Method clearMethod = cache.getClass().getMethod("clear");
                    clearMethod.invoke(cache);
                }
            } finally {
                cacheField.setAccessible(false);
            }
        } catch (Exception e) {
            if (mDebug) Log.d(TAG, "Couldn't clear PackageInfoCache: " + e);
        }

        try {
            Field creatorsField = findField(Parcel.class, "mCreators");
            creatorsField.setAccessible(true);
            try {
                Map<?, ?> mCreators = (Map<?, ?>) creatorsField.get(null);
                if (mCreators != null) mCreators.clear();
            } finally {
                creatorsField.setAccessible(false);
            }
        } catch (Exception e) {
            if (mDebug) Log.d(TAG, "Couldn't clear Parcel mCreators: " + e);
        }

        try {
            Field creatorsField = findField(Parcel.class, "sPairedCreators");
            creatorsField.setAccessible(true);
            try {
                Map<?, ?> sPairedCreators = (Map<?, ?>) creatorsField.get(null);
                if (sPairedCreators != null) sPairedCreators.clear();
            } finally {
                creatorsField.setAccessible(false);
            }
        } catch (Exception e) {
            if (mDebug) Log.d(TAG, "Couldn't clear Parcel sPairedCreators: " + e);
        }

        mSignatureSpoofed = true;
        Log.i(TAG, "Signature spoofing enabled via Parcelable.Creator");
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> currentClass = clazz;
        while (currentClass != null && !currentClass.equals(Object.class)) {
            try {
                return currentClass.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                currentClass = currentClass.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + fieldName + "' not found");
    }

    private static boolean initUnsafe() {
        synchronized (sUnsafeLock) {
            if (sUnsafeLookedUp) return sUnsafe != null && sPutObject != null
                    && (sFieldOffset != null || sFieldGetOffset != null);
            sUnsafeLookedUp = true;

            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Object unsafe = null;
                for (String name : new String[] { "theUnsafe", "THE_ONE" }) {
                    try {
                        Field f = unsafeClass.getDeclaredField(name);
                        f.setAccessible(true);
                        unsafe = f.get(null);
                        if (unsafe != null) break;
                    } catch (Throwable ignored) {
                    }
                }
                if (unsafe == null) {
                    try {
                        unsafe = unsafeClass.getMethod("getUnsafe").invoke(null);
                    } catch (Throwable ignored) {
                    }
                }
                if (unsafe == null) {
                    Log.w(TAG, "sun.misc.Unsafe instance unavailable");
                    return false;
                }

                sPutObject = unsafeClass.getMethod("putObject",
                        Object.class, long.class, Object.class);
                sPutInt = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
                sPutLong = unsafeClass.getMethod("putLong", Object.class, long.class, long.class);
                try {
                    sPutBoolean = unsafeClass.getMethod("putBoolean",
                            Object.class, long.class, boolean.class);
                } catch (NoSuchMethodException ignored) {
                }
                sUnsafe = unsafe;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to resolve sun.misc.Unsafe: " + t);
                sUnsafe = null;
                return false;
            }

            try {
                Field f = Field.class.getDeclaredField("offset");
                f.setAccessible(true);
                sFieldOffset = f;
            } catch (Throwable ignored) {
            }
            if (sFieldOffset == null) {
                try {
                    Method m = Field.class.getDeclaredMethod("getOffset");
                    m.setAccessible(true);
                    sFieldGetOffset = m;
                } catch (Throwable ignored) {
                }
            }
            if (sFieldOffset == null && sFieldGetOffset == null) {
                Log.w(TAG, "Unable to resolve java.lang.reflect.Field offset");
                return false;
            }
            return true;
        }
    }

    private static long fieldOffset(Field field) throws ReflectiveOperationException {
        if (sFieldOffset != null) {
            return ((Number) sFieldOffset.get(field)).longValue();
        }
        return ((Number) sFieldGetOffset.invoke(field)).longValue();
    }

    private static void putStaticViaUnsafe(Field field, Object value)
            throws ReflectiveOperationException {
        if (!initUnsafe()) {
            throw new IllegalStateException("Unsafe fallback unavailable");
        }
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException(field.getName() + " is not static");
        }

        Object base = field.getDeclaringClass();
        long offset = fieldOffset(field);
        Class<?> type = field.getType();

        if (!type.isPrimitive()) {
            sPutObject.invoke(sUnsafe, base, offset, value);
        } else if (type == int.class) {
            sPutInt.invoke(sUnsafe, base, offset, ((Number) value).intValue());
        } else if (type == long.class) {
            sPutLong.invoke(sUnsafe, base, offset, ((Number) value).longValue());
        } else if (type == boolean.class && sPutBoolean != null) {
            sPutBoolean.invoke(sUnsafe, base, offset, (Boolean) value);
        } else {
            throw new IllegalArgumentException("Unsupported static type " + type);
        }
    }

    private static void setStaticField(Field field, Object value) throws Exception {
        field.setAccessible(true);
        try {
            try {
                field.set(null, value);
            } catch (IllegalAccessException e) {
                putStaticViaUnsafe(field, value);
            }
            Object now = field.get(null);
            if (!Objects.equals(now, value)) {
                throw new IllegalStateException("write to " + field.getName()
                        + " did not stick (still " + now + ")");
            }
        } finally {
            field.setAccessible(false);
        }
    }

    private void spoofSdkInt() {
        int requestedSdk = mSpoofVendingSdk == 1 ? 32 : mSpoofVendingSdk;
        int targetSdk = Math.min(Build.VERSION.SDK_INT, requestedSdk);

        Field field;
        try {
            field = Build.VERSION.class.getDeclaredField("SDK_INT");
        } catch (Exception e) {
            Log.e(TAG, "Failed to spoof SDK_INT", e);
            return;
        }

        field.setAccessible(true);
        try {
            int oldValue = field.getInt(null);
            if (oldValue != targetSdk) {
                setStaticField(field, targetSdk);
                Log.d(TAG + "/Java:DG", "[SDK_INT]: " + oldValue + " -> " + targetSdk);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to spoof SDK_INT", e);
        } finally {
            field.setAccessible(false);
        }
    }

    private boolean spoofField(String fieldName, String value, String logSuffix) {
        if (value == null || value.isEmpty()) {
            if (mVerboseLogs > 0) Log.d(TAG, fieldName + " is empty, skipping");
            return false;
        }

        Field field;
        try {
            if (hasField(Build.class, fieldName)) {
                field = Build.class.getDeclaredField(fieldName);
            } else if (hasField(Build.VERSION.class, fieldName)) {
                field = Build.VERSION.class.getDeclaredField(fieldName);
            } else {
                if (mVerboseLogs > 1) Log.d(TAG, "Field not found: " + fieldName);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to locate field " + fieldName, e);
            return false;
        }

        field.setAccessible(true);
        try {
            String oldValue = String.valueOf(field.get(null));

            if (value.equals(oldValue)) {
                if (mVerboseLogs > 2) Log.d(TAG, "[" + fieldName + "]: " + value + " (unchanged)");
                return true;
            }

            Class<?> fieldType = field.getType();
            Object newValue;

            if (fieldType == String.class) {
                newValue = value;
            } else if (fieldType == int.class) {
                newValue = Integer.parseInt(value);
            } else if (fieldType == long.class) {
                newValue = Long.parseLong(value);
            } else if (fieldType == boolean.class) {
                newValue = Boolean.parseBoolean(value);
            } else {
                Log.w(TAG, "Unsupported field type: " + fieldType);
                return false;
            }

            setStaticField(field, newValue);
            if (mVerboseLogs > 0 || mDebug) {
                Log.d(TAG + "/Java:" + logSuffix, "[" + fieldName + "]: " + oldValue + " -> " + value);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to spoof " + fieldName, e);
            return false;
        } finally {
            field.setAccessible(false);
        }
    }

    private boolean hasField(Class<?> clazz, String fieldName) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getName().equals(fieldName)) return true;
        }
        return false;
    }

    public boolean isSpoofSignatureEnabled() {
        ensureLoaded();
        return mSpoofSignature && mConfigLoaded;
    }

    public boolean isSpoofProviderEnabled() {
        ensureLoaded();
        return mSpoofProvider && mConfigLoaded;
    }

    public void spoofProvider() {
        spoofProvider(DROIDGUARD_PACKAGE);
    }

    public void spoofProvider(String processName) {
        if (!isSpoofProviderEnabled() || !isDroidGuard(processName)) return;
        sBlockCertificateChain = true;
        Log.i(TAG, "Certificate chain block enabled for DroidGuard");
    }

    public static boolean shouldBlockCertificateChain() {
        if (!sBlockCertificateChain) return false;
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            if (e.getClassName().toLowerCase(Locale.ROOT).contains("droidguard")) {
                return true;
            }
        }
        return false;
    }

    public int getVerboseLogs() {
        return mVerboseLogs;
    }

    public Map<String, String> getBuildFields() {
        return mBuildFields;
    }

    public String getSpoofVendingFinger() {
        return mSpoofVendingFinger;
    }

    public boolean isConfigLoaded() {
        return mConfigLoaded;
    }

    public boolean isSpoofBuildEnabled() {
        ensureLoaded();
        return mSpoofBuild && mConfigLoaded;
    }

    public byte[] getRomSignatureBytes() {
        return Base64.decode(ROM_SIGNATURE_DATA, Base64.DEFAULT);
    }

    public boolean shouldSpoofPhotos(String packageName) {
        if (!isPhotosPackage(packageName)) return false;
        ensureLoaded();
        return mSpoofPhotos;
    }

    public void spoofPhotosProps() {
        int ok = 0;
        for (Map.Entry<String, Object> entry : PIXEL_XL_PROPS.entrySet()) {
            if (spoofField(entry.getKey(), String.valueOf(entry.getValue()), "Photos")) ok++;
        }
        if (ok == PIXEL_XL_PROPS.size()) {
            Log.i(TAG, "Photos spoofing enabled - device appears as Pixel XL");
        } else {
            Log.w(TAG, "Photos spoofing partial: " + ok + "/" + PIXEL_XL_PROPS.size()
                    + " fields applied");
        }
    }

    public Boolean hasSystemFeature(String name, int version) {
        if (name == null) return null;

        final String pkgName = ActivityThread.currentPackageName();
        if (pkgName != null && PRIV_PKGS.contains(pkgName)) {
            if (shouldSpoofPhotos(pkgName)) {
                if (FEATURES_PIXEL.contains(name)) return false;
                if (FEATURES_PIXEL_OTHERS.contains(name)) return true;
                if (FEATURES_TENSOR.contains(name)) return false;
                if (FEATURES_NEXUS.contains(name)) return true;
            } else {
                if (FEATURES_PIXEL.contains(name)) return true;
                if (FEATURES_PIXEL_OTHERS.contains(name)) return true;
                if (FEATURES_TENSOR.contains(name)) return true;
                if (FEATURES_NEXUS.contains(name)) return true;
            }
        }

        if (FEATURES_PIXEL.contains(name)) return true;
        if (FEATURES_PIXEL_OTHERS.contains(name)) return true;

        return null;
    }

    public void logBuildFields() {
        if (mVerboseLogs < 100) return;
        for (Field field : Build.class.getDeclaredFields()) {
            try {
                Log.d(TAG, "Build." + field.getName() + " = " + field.get(null));
            } catch (Exception e) {
            }
        }
        for (Field field : Build.VERSION.class.getDeclaredFields()) {
            try {
                Log.d(TAG, "Build.VERSION." + field.getName() + " = " + field.get(null));
            } catch (Exception e) {
            }
        }
    }

    private static class CustomPackageInfoCreator implements Parcelable.Creator<PackageInfo> {
        private final Parcelable.Creator<PackageInfo> originalCreator;
        private final Signature spoofedSignature;

        CustomPackageInfoCreator(Parcelable.Creator<PackageInfo> originalCreator, Signature spoofedSignature) {
            this.originalCreator = originalCreator;
            this.spoofedSignature = spoofedSignature;
        }

        @Override
        @SuppressWarnings("deprecation")
        public PackageInfo createFromParcel(Parcel source) {
            PackageInfo packageInfo = originalCreator.createFromParcel(source);
            if ("android".equals(packageInfo.packageName)) {
                if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                    packageInfo.signatures[0] = spoofedSignature;
                }
                if (packageInfo.signingInfo != null) {
                    Signature[] signaturesArray = packageInfo.signingInfo.getApkContentsSigners();
                    if (signaturesArray != null && signaturesArray.length > 0) {
                        signaturesArray[0] = spoofedSignature;
                    }
                }
            }
            return packageInfo;
        }

        @Override
        public PackageInfo[] newArray(int size) {
            return originalCreator.newArray(size);
        }
    }
}
