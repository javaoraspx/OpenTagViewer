package dev.wander.android.opentagviewer;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.amap.api.maps.MapsInitializer;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.google.android.material.color.DynamicColors;

import dev.wander.android.opentagviewer.anisette.AppleLibraryProbeService;
import dev.wander.android.opentagviewer.anisette.MacAdiService;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.service.NearbyScanService;
import dev.wander.android.opentagviewer.util.rx.UndeliverableErrors;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public class OpenAirTagApplication extends Application {
    private static final String TAG = OpenAirTagApplication.class.getSimpleName();

    @Override
    public void onCreate() {
        super.onCreate();

        // **The process that tries Apple's library first gets none of this.** It runs this class
        // like any other process of the app, and it exists to load one library and possibly die.
        // Starting Python there would unpack Chaquopy's assets from two processes at once, and
        // resuming the background scan would start a second scanner. See AppleLibraryProbeService.
        // The emulated Mac's process is the same: it exists to run one emulator. See MacAdiService.
        if (isTheAppleLibraryProbe() || isTheEmulatedMac()) {
            return;
        }

        // A fetch that fails after its screen was rebuilt must not take the app down with it.
        RxJavaPlugins.setErrorHandler(UndeliverableErrors.handler());

        // What PyApplication did, which this used to extend. Done by hand so the line above can
        // come first.
        Python.start(new AndroidPlatform(this));

        // 高德地图 SDK 初始化（Key + 隐私合规），必须早于任何地图对象创建。
        this.initAMap();

        this.setupTheme();
        this.setupSystemColors();
        this.resumeBackgroundScanIfEnabled();
    }

    private boolean isTheAppleLibraryProbe() {
        return this.processName().endsWith(AppleLibraryProbeService.PROCESS_SUFFIX);
    }

    private boolean isTheEmulatedMac() {
        return this.processName().endsWith(MacAdiService.PROCESS_SUFFIX);
    }

    private String processName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        try (FileInputStream cmdline = new FileInputStream("/proc/self/cmdline")) {
            final byte[] buffer = new byte[256];
            final int read = cmdline.read(buffer);
            int end = 0;
            while (end < Math.max(read, 0) && buffer[end] != 0) {
                end++;
            }
            return new String(buffer, 0, end, StandardCharsets.UTF_8);
        } catch (final Exception e) {
            Log.w(TAG, "Could not read this process's name; assuming the main process", e);
            return this.getPackageName();
        }
    }

    /**
     * Brings the background scan back after the process was gone.
     *
     * <p><b>The setting is the state, and the service is only its consequence.</b> A service does
     * not survive a reboot, a force-stop or the system reclaiming memory, so without this the
     * switch would silently stop meaning anything and the only cure would be toggling it off and
     * on - which reads as the setting having been forgotten.
     *
     * <p>Off by default, so this starts nothing for anyone who has not asked. Read
     * asynchronously because the setting lives in a DataStore and Application#onCreate blocks
     * the first activity.
     */
    private void resumeBackgroundScanIfEnabled() {
        // Off the main thread: the read hits a DataStore, and this runs before the first
        // activity is created.
        Schedulers.io().scheduleDirect(() -> {
            try {
                final UserSettings settings =
                        new UserSettingsRepository(UserSettingsDataStore.getInstance(this))
                                .getUserSettings();

                if (settings.shouldScanInBackground()) {
                    Log.i(TAG, "Background scanning is on; starting the service");
                    NearbyScanService.start(this);
                }
            } catch (final Exception e) {
                Log.w(TAG, "Could not read whether to scan in the background", e);
            }
        });
    }

    /**
     * Whether to colour activities from the user's wallpaper.
     *
     * <p>Read by the precondition below every time an activity is created, which is what lets
     * the Settings switch take effect in both directions. Volatile because it is written from
     * the UI thread and read during activity creation.
     */
    private static volatile boolean useSystemColors = false;

    /**
     * Arranges for activities to be coloured from the user's wallpaper when they have asked
     * for that.
     *
     * <p>Registration happens unconditionally and exactly once, because it cannot be undone -
     * {@code applyToActivitiesIfAvailable} adds an {@code ActivityLifecycleCallbacks} to the
     * process and Material offers no way to remove it. Registering with a <b>precondition</b>
     * instead of registering conditionally is what makes turning the setting back off work:
     * the precondition is consulted per activity, so a recreated activity picks up the
     * current answer rather than the one that was true at startup.
     *
     * <p>It must also happen before the first activity is created, since the callback only
     * sees activities created after it. That includes the login screen, which is the first
     * thing a new user sees and the one they cannot reach Settings from.
     *
     * <p>Reading the setting synchronously here is what {@link #setupTheme()} already does,
     * and for the same reason: there is nothing drawing yet to block.
     *
     * <p>The version check is left to Material, whose registration is a no-op below Android
     * 12. The Settings entry is hidden there, so the flag should never be set on such a
     * device - but a value can arrive from a backup restore, and being defensive is cheaper
     * than reasoning about it.
     */
    private void setupSystemColors() {
        try {
            var repository = new UserSettingsRepository(
                    UserSettingsDataStore.getInstance(this.getApplicationContext()));

            final Boolean stored = repository.getUserSettings().getUseSystemColors();

            if (stored != null) {
                useSystemColors = stored;
            } else {
                // Nobody has chosen. A first install should look like the phone it is on; an
                // update should look like it did yesterday. See UserSettings.useSystemColors.
                useSystemColors = this.isFirstRun();

                // Recorded now so the answer does not change out from under the user later:
                // the database appears as soon as anything reads it, so isFirstRun() is only
                // ever true during this one launch, and an unresolved setting would silently
                // flip their app back to the app palette on the next.
                var async = repository.storeUseSystemColors(useSystemColors)
                        .subscribe(() -> { },
                                error -> Log.e(TAG, "Could not record the system colours default", error));
            }
        } catch (Exception e) {
            // The app's own palette is a working fallback, and a theme is not worth failing
            // to start over.
            Log.e(TAG, "Failed to read the system colours setting; keeping the app palette", e);
            useSystemColors = false;
        }

        DynamicColors.applyToActivitiesIfAvailable(
                this, (activity, themeResId) -> useSystemColors);

        Log.i(TAG, "System colours available=" + DynamicColors.isDynamicColorAvailable()
                + " enabled=" + useSystemColors);
    }

    /**
     * Whether nobody has used this app on this device yet.
     *
     * <p>The signal is whether the database file exists. Room creates it the first time anything
     * reads from it, so it is absent only before the first run has got as far as looking at a
     * beacon or a session - which is exactly "new user".
     *
     * <p>Package install timestamps were tried first and are wrong for this. They answer "was
     * this package ever updated", not "has this person used the app". Clearing the app's data
     * leaves both timestamps untouched, so somebody starting over - or a developer with
     * "clear app data before launch" set - is reported as an existing user and quietly gets the
     * old default. The database is a property of the data, so it follows the data.
     *
     * <p>Errs towards {@code false} - the app palette - because that is the answer that changes
     * nothing for somebody who already had the app.
     */
    private boolean isFirstRun() {
        try {
            return !this.getDatabasePath(OpenTagViewerDatabase.DATABASE_NAME).exists();
        } catch (Exception e) {
            Log.e(TAG, "Could not tell whether this is a first run", e);
            return false;
        }
    }

    /**
     * Switches wallpaper colouring on or off for activities created from now on.
     *
     * <p>Callers are expected to recreate whatever is on screen; everything behind it is
     * recreated by Android when it returns to the foreground.
     */
    public static void setUseSystemColors(final boolean enabled) {
        useSystemColors = enabled;
    }

    /**
     * 高德地图的初始化：API Key + 隐私合规。
     *
     * <p><b>直接调用 SDK，不再走反射。</b>以前整套都是反射，理由是"没有谷歌地图时也要能跑"，
     * 但这个包已经只带高德了：反射换不来可选的依赖，只会让 R8 看不到调用点（于是整包保留、
     * 体积变大、也更慢），并且把所有真实错误都吞成了一个 {@code catch (Exception)}。
     *
     * <p>Key 仍然先设：{@code MapsInitializer} 是静态状态，必须先于任何地图对象创建写入，
     * 否则 SDK 会用空 Key 去请求瓦片，表现为一片空白而不是报错。
     *
     * <p>隐私合规是《个人信息保护法》与高德开放平台条款的共同要求：
     * {@code updatePrivacyShow} / {@code updatePrivacyAgree} 必须在调用 SDK 任何其他接口之前
     * 完成。参考：https://lbs.amap.com/api/android-sdk/guide/create-map/dev-attention
     */
    private void initAMap() {
        // 这个包不带任何高德 Key：Key 按开发者账号签发并绑定包名与签名指纹，条款上也要求
        // 持有者是应用的运营方，所以由用户在设置里自己填，与 Anisette 服务器地址的处理一致。
        this.applyUserSuppliedAMapKey();

        try {
            // isContains: 隐私政策是否包含高德开放平台隐私政策
            // isShow: 隐私政策是否已弹窗告知用户
            MapsInitializer.updatePrivacyShow(this, true, true);
            // isAgree: 用户是否已同意隐私政策
            MapsInitializer.updatePrivacyAgree(this, true);
            Log.i(TAG, "AMap privacy compliance configured");
        } catch (final Throwable e) {
            // **Throwable, not Exception.** This runs in Application.onCreate, before any UI
            // exists: an Error or a native failure escaping here kills the app on every launch
            // with nothing on screen to explain it. A map that cannot start is a broken feature;
            // an app that cannot start is a broken app.
            Log.e(TAG, "Could not configure AMap privacy compliance", e);
        }
    }

    /** 把用户自己填的高德 Key 交给 SDK（必须在创建地图之前）。 */
    private void applyUserSuppliedAMapKey() {
        final UserSettings settings;
        try {
            settings = new UserSettingsRepository(
                    UserSettingsDataStore.getInstance(this.getApplicationContext())
            ).getUserSettings();
        } catch (final Exception e) {
            Log.e(TAG, "Could not read settings to look up the AMap API key", e);
            return;
        }

        if (settings == null || !settings.hasAmapApiKey()) {
            // 用户的常态：还没在设置里填 Key。地图会显示空白底图，设置页里有引导。
            Log.i(TAG, "No AMap API key configured yet; map tiles will be blank until one is set");
            return;
        }

        try {
            MapsInitializer.setApiKey(settings.getAmapApiKey().trim());
            Log.i(TAG, "Applied user-supplied AMap API key");
        } catch (final Throwable e) {
            Log.e(TAG, "AMap rejected the supplied API key", e);
        }
    }

    public void setupTheme() {
        final int currentNightMode = this.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

        var userSettingsRepo = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.getApplicationContext()));

        var userSettings = userSettingsRepo.getUserSettings();
        final Boolean useDarkTheme = userSettings.getUseDarkTheme();

        if (useDarkTheme == null) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            return;
        }

        if (currentNightMode == Configuration.UI_MODE_NIGHT_NO && useDarkTheme == Boolean.TRUE) {
            Log.i(TAG, "Updating to app dark theme choice");
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else if (currentNightMode == Configuration.UI_MODE_NIGHT_YES && useDarkTheme == Boolean.FALSE) {
            Log.i(TAG, "Updating to app light theme choice");
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }
}
