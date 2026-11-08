package me.meiser79.intercomsatellite;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Asks for what calls need: notifications, the microphone, the camera for video calls, background
 * reception, the phone account and calls on the lock screen. Each question comes once per install
 * or update, as installing an update can take a permission back; the optional phone account only
 * once. A question
 * put off goes on to the next at once; one answered on a system screen goes on when the app is
 * back, so that answer is in first. Settings keeps an entry for whatever is still missing.
 */
final class SetupChecklist {
    private SetupChecklist() {}

    static boolean batteryMissing(Context context) {
        return !context.getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** Only matters while calls ring in the app; the phone app shows its own calls. */
    static boolean fullScreenMissing(Context context) {
        return Build.VERSION.SDK_INT >= 34
                && !PhoneIntegration.ready(context)
                && !context.getSystemService(NotificationManager.class).canUseFullScreenIntent();
    }

    /** Asks the next open question, if any. */
    static void next(AppCompatActivity activity, Config config, PermissionHelper permissions) {
        Runnable later = () -> next(activity, config, permissions);
        if (Build.VERSION.SDK_INT >= 33
                && askAfterUpdate(
                        activity,
                        config,
                        "setupNotificationsAt",
                        !permissions.has(Manifest.permission.POST_NOTIFICATIONS))) {
            permissions.require(
                    Manifest.permission.POST_NOTIFICATIONS,
                    R.string.toast_notifications_denied,
                    true,
                    later);
        } else if (askAfterUpdate(
                activity,
                config,
                "setupMicrophoneAt",
                !permissions.has(Manifest.permission.RECORD_AUDIO))) {
            permissions.require(
                    Manifest.permission.RECORD_AUDIO,
                    R.string.toast_mic_permission_denied,
                    true,
                    later);
        } else if (askAfterUpdate(
                activity,
                config,
                "setupCameraAt",
                config.video() && !permissions.has(Manifest.permission.CAMERA))) {
            permissions.require(
                    Manifest.permission.CAMERA,
                    R.string.toast_camera_permission_denied,
                    true,
                    later);
        } else if (askAfterUpdate(activity, config, "setupBatteryAt", batteryMissing(activity))) {
            battery(activity, later);
        } else if (ask(config, "setupPhone", !PhoneIntegration.ready(activity))) {
            question(
                    activity,
                    R.string.setup_phone_title,
                    R.string.setup_phone_message,
                    R.string.setup_phone_action,
                    () -> PhoneSetupFlow.start(activity, permissions),
                    later);
        } else if (askAfterUpdate(
                activity, config, "setupFullScreenAt", fullScreenMissing(activity))) {
            fullScreen(activity, later);
        }
    }

    /** True once for a question that is still open; it is then marked as asked. */
    private static boolean ask(Config config, String key, boolean missing) {
        if (!missing || config.prefs.getBoolean(key, false)) return false;
        config.prefs.edit().putBoolean(key, true).apply();
        return true;
    }

    /** Like ask(), but open again once the app was installed anew or updated. */
    private static boolean askAfterUpdate(
            Context context, Config config, String key, boolean missing) {
        long installed;
        try {
            installed =
                    context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0)
                            .lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            installed = 0;
        }
        if (!missing || config.prefs.getLong(key, -1) == installed) return false;
        config.prefs.edit().putLong(key, installed).apply();
        return true;
    }

    static void battery(AppCompatActivity activity) {
        battery(activity, null);
    }

    private static void battery(AppCompatActivity activity, Runnable later) {
        question(
                activity,
                R.string.battery_title,
                R.string.battery_message,
                R.string.open_setting,
                () ->
                        activity.startActivity(
                                new Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:" + activity.getPackageName()))),
                later);
    }

    static void fullScreen(AppCompatActivity activity) {
        fullScreen(activity, null);
    }

    private static void fullScreen(AppCompatActivity activity, Runnable later) {
        question(
                activity,
                R.string.row_fullscreen,
                R.string.fullscreen_message,
                R.string.open_setting,
                () ->
                        activity.startActivity(
                                new Intent(
                                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.parse("package:" + activity.getPackageName()))),
                later);
    }

    /** later runs when the question is put off, by its button or by closing it. */
    private static void question(
            AppCompatActivity activity,
            int title,
            int message,
            int action,
            Runnable act,
            Runnable later) {
        boolean[] acted = {false};
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(R.string.later, null)
                .setPositiveButton(
                        action,
                        (d, w) -> {
                            acted[0] = true;
                            act.run();
                        })
                .setOnDismissListener(
                        d -> {
                            if (!acted[0] && later != null && !activity.isFinishing()) later.run();
                        })
                .show();
    }
}
