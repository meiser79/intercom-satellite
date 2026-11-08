package me.meiser79.intercomsatellite;

import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.content.IntentCompat;

/**
 * Ringtone choices as stored in {@link Config}: null for the default (the app's ringtone for a
 * kiosk, the phone's for the app), empty for silence, otherwise a ringtone's URI.
 */
final class Ringtones {
    private Ringtones() {}

    /**
     * Android's ringtone picker, opened on {@code current}. Its Default entry stands for
     * {@code fallback}, what rings while nothing is chosen here.
     */
    static Intent picker(Context context, String current, Uri fallback) {
        return new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, fallback)
                .putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        current == null
                                ? fallback
                                : current.isEmpty() ? null : Uri.parse(current))
                .putExtra(
                        RingtoneManager.EXTRA_RINGTONE_TITLE,
                        context.getString(R.string.field_room_ringtone));
    }

    /** The choice the picker returned; its Default entry becomes null. */
    static String picked(Intent data, Uri fallback) {
        Uri uri =
                IntentCompat.getParcelableExtra(
                        data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.class);
        if (uri == null) return "";
        return uri.equals(fallback) ? null : uri.toString();
    }

    /** What rings for a kiosk that has no ringtone of its own. */
    static Uri appFallback(Config config) {
        String app = config.appRingtone();
        return app == null || app.isEmpty()
                ? Settings.System.DEFAULT_RINGTONE_URI
                : Uri.parse(app);
    }

    static String title(Context context, String choice, int defaultLabel) {
        if (choice == null) return context.getString(defaultLabel);
        if (choice.isEmpty()) return context.getString(R.string.ringtone_silent);
        try {
            Ringtone tone = RingtoneManager.getRingtone(context, Uri.parse(choice));
            if (tone != null) return tone.getTitle(context);
        } catch (RuntimeException ignored) {
            // A ringtone that was removed or cannot be read.
        }
        return context.getString(R.string.ringtone_custom);
    }

    /** What rings for a call from {@code kioskId}; null when it rings silently. */
    static Uri forCall(Config config, String kioskId) {
        String choice = kioskId == null ? null : config.ringtone(kioskId);
        if (choice == null) choice = config.appRingtone();
        if (choice == null) return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        return choice.isEmpty() ? null : Uri.parse(choice);
    }
}
