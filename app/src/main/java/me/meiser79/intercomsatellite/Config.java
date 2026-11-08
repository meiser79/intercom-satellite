package me.meiser79.intercomsatellite;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Config {
    final SharedPreferences prefs;
    private final LongSupplier clock;

    private String unknownKiosk = "Kiosk";
    private String defaultName = "My smartphone";
    /** Whether Android's Do not disturb is on; replaced in tests. */
    BooleanSupplier androidDnd = () -> false;

    Config(Context c) {
        this(c.getSharedPreferences("intercom", Context.MODE_PRIVATE), System::currentTimeMillis);
        unknownKiosk = c.getString(R.string.unknown_kiosk);
        defaultName = c.getString(R.string.default_device_name);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        // Priority only, alarms only and total silence all count as Do not disturb.
        androidDnd =
                () ->
                        nm != null
                                && nm.getCurrentInterruptionFilter()
                                        > NotificationManager.INTERRUPTION_FILTER_ALL;
        if (!installChecked) checkInstall(c, prefs);
    }

    private static volatile boolean installChecked;

    /**
     * Android backup restores the settings onto a new phone or a reinstall, but this phone's own
     * identity must not come along: its ID would clash with the old phone's, the shared key was
     * encrypted with a key that never leaves the old phone's keystore, and widget IDs are per
     * launcher. A marker outside the backup tells a restore from this installation's own data.
     */
    private static synchronized void checkInstall(Context c, SharedPreferences prefs) {
        if (installChecked) return;
        File marker = new File(c.getNoBackupFilesDir(), "installed");
        if (!marker.exists()) {
            SharedPreferences.Editor edit = prefs.edit();
            if (prefs.getBoolean("installed", false)) {
                edit.putString("id", UUID.randomUUID().toString()).remove("secret").remove("shortcuts");
                for (String key : prefs.getAll().keySet())
                    if (key.startsWith("widget:") || key.startsWith("announceWidget:"))
                        edit.remove(key);
            }
            edit.putBoolean("installed", true).commit();
            try {
                marker.createNewFile();
            } catch (IOException ignored) {
                // Checked again next time.
                return;
            }
        }
        installChecked = true;
    }

    /** Placeholder name for a kiosk that is neither aliased nor remembered. */
    String unknownKiosk() {
        return unknownKiosk;
    }

    Config(SharedPreferences prefs, LongSupplier clock) {
        this.prefs = prefs;
        this.clock = clock;
        if (!prefs.contains("id"))
            prefs.edit().putString("id", UUID.randomUUID().toString()).apply();
    }

    String id() {
        return prefs.getString("id", "");
    }

    String name() {
        return prefs.getString("name", defaultName);
    }

    boolean tls() {
        return prefs.getBoolean("tls", false);
    }

    boolean dnd() {
        long until = dndUntil();
        return prefs.getBoolean("dnd", false) && (until == 0 || clock.getAsLong() < until);
    }

    boolean followAndroidDnd() {
        return prefs.getBoolean("followAndroidDnd", false);
    }

    void setFollowAndroidDnd(boolean on) {
        prefs.edit().putBoolean("followAndroidDnd", on).apply();
    }

    /** Android's own Do not disturb is on and this app follows it. */
    boolean androidDnd() {
        return followAndroidDnd() && androidDnd.getAsBoolean();
    }

    long dndUntil() {
        return prefs.getLong("dndUntil", 0);
    }

    void setDnd(boolean on) {
        prefs.edit().putBoolean("dnd", on).remove("dndUntil").apply();
    }

    void snoozeUntil(long until) {
        if (until <= clock.getAsLong())
            throw new IllegalArgumentException("End time must be in the future");
        prefs.edit().putBoolean("dnd", true).putLong("dndUntil", until).apply();
    }

    boolean acceptAnnouncements() {
        return prefs.getBoolean("acceptAnnouncements", true);
    }

    /** Whether calls here carry video at all; the camera still only goes on with a tap. */
    boolean video() {
        return prefs.getBoolean("video", true);
    }

    void setVideo(boolean on) {
        prefs.edit().putBoolean("video", on).apply();
    }

    /** low, medium or high, see {@link CameraSender#quality}. */
    String videoQuality() {
        return prefs.getString("videoQuality", "medium");
    }

    void setVideoQuality(String quality) {
        prefs.edit().putString("videoQuality", quality).apply();
    }

    /** Whether calls start on the loudspeaker instead of the earpiece. */
    boolean speakerDefault() {
        return prefs.getBoolean("speakerDefault", false);
    }

    void setSpeakerDefault(boolean on) {
        prefs.edit().putBoolean("speakerDefault", on).apply();
    }

    /** Whether calls start hands-free, the microphone open; push to talk otherwise. */
    boolean handsFreeDefault() {
        return prefs.getBoolean("handsFreeDefault", false);
    }

    void setHandsFreeDefault(boolean on) {
        prefs.edit().putBoolean("handsFreeDefault", on).apply();
    }

    boolean autoStart() {
        return prefs.getBoolean("autoStart", false);
    }

    boolean enabledBefore() {
        return prefs.getBoolean("enabledBefore", false);
    }

    boolean quiet() {
        return QuietHours.active(
                prefs.getBoolean("quietEnabled", false),
                prefs.getInt("quietDays", 127),
                prefs.getInt("quietStart", 1320),
                prefs.getInt("quietEnd", 420),
                LocalDateTime.now());
    }

    boolean blocks(String id) {
        return dnd() || androidDnd() || (quiet() && !exceptions().contains(id));
    }

    boolean advertisedDnd() {
        return dnd() || androidDnd() || (quiet() && exceptions().isEmpty());
    }

    Set<String> exceptions() {
        return prefs.getStringSet("quietExceptions", Collections.emptySet());
    }

    String alias(String id) {
        return prefs.getString("alias:" + id, "");
    }

    String icon(String id) {
        return RoomIcons.name(prefs.getString("icon:" + id, null));
    }

    /** Null for the app's ringtone, empty for silence, otherwise a ringtone's URI. */
    String ringtone(String id) {
        return prefs.getString("ringtone:" + id, null);
    }

    void setRingtone(String id, String uri) {
        if (uri == null) prefs.edit().remove("ringtone:" + id).apply();
        else prefs.edit().putString("ringtone:" + id, uri).apply();
    }

    /** Null for the phone's ringtone, empty for silence, otherwise a ringtone's URI. */
    String appRingtone() {
        return prefs.getString("ringtone", null);
    }

    void setAppRingtone(String uri) {
        if (uri == null) prefs.edit().remove("ringtone").apply();
        else prefs.edit().putString("ringtone", uri).apply();
    }

    boolean favorite(String id) {
        return prefs.getBoolean("favorite:" + id, false);
    }

    String display(Peer p) {
        String alias = alias(p.id);
        if (!alias.isEmpty()) return alias;
        return p.name.isEmpty() ? unknownKiosk : p.name;
    }

    /** Saves a kiosk found on the network; true when what a widget shows of it changed. */
    boolean remember(Peer p) {
        if (p == null || p.id == null || p.id.isEmpty()) return false;
        try {
            JSONArray old = new JSONArray(prefs.getString("knownPeers", "[]")),
                    next = new JSONArray();
            JSONObject before = null;
            for (int i = 0; i < old.length(); i++) {
                JSONObject j = old.optJSONObject(i);
                if (j != null && p.id.equals(j.optString("id"))) before = j;
            }
            next.put(
                    Wire.obj(
                            "id",
                            p.id,
                            "name",
                            p.name,
                            "host",
                            p.host,
                            "port",
                            p.adminPort,
                            "tls",
                            p.adminTls,
                            "video",
                            p.video));
            for (int i = 0; i < old.length() && next.length() < 64; i++) {
                JSONObject j = old.optJSONObject(i);
                if (j != null && !p.id.equals(j.optString("id"))) next.put(j);
            }
            prefs.edit().putString("knownPeers", next.toString()).apply();
            return before == null
                    || !p.name.equals(before.optString("name"))
                    || p.video != before.optBoolean("video");
        } catch (Exception ignored) {
            return false;
        }
    }

    JSONArray known() {
        try {
            return new JSONArray(prefs.getString("knownPeers", "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    String peerName(String id) {
        String alias = alias(id);
        if (!alias.isEmpty()) return alias;
        JSONArray peers = known();
        for (int i = 0; i < peers.length(); i++) {
            JSONObject p = peers.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p.optString("name", unknownKiosk);
        }
        return unknownKiosk;
    }

    String key() {
        try {
            String v = prefs.getString("secret", "");
            if (v.isEmpty()) return "";
            String[] a = v.split(":");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    storageKey(),
                    new GCMParameterSpec(128, Base64.decode(a[0], Base64.NO_WRAP)));
            return new String(
                    cipher.doFinal(Base64.decode(a[1], Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    void save(String name, String key, boolean tls) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, storageKey());
        String secret =
                Base64.encodeToString(c.getIV(), Base64.NO_WRAP)
                        + ":"
                        + Base64.encodeToString(
                                c.doFinal(key.getBytes(StandardCharsets.UTF_8)),
                                Base64.NO_WRAP);
        prefs.edit()
                .putString("name", name)
                .putString("secret", secret)
                .putBoolean("tls", tls)
                .apply();
    }

    // ---- UI-related settings (typed accessors instead of raw pref keys in the UI) ----
    String themeMode() {
        return prefs.getString("theme", "system");
    }

    void setThemeMode(String mode) {
        prefs.edit().putString("theme", mode).apply();
    }

    void setFavorite(String id, boolean favorite) {
        prefs.edit().putBoolean("favorite:" + id, favorite).apply();
    }

    void setAliasAndIcon(String id, String alias, String icon) {
        prefs.edit().putString("alias:" + id, alias).putString("icon:" + id, icon).apply();
    }

    void setAcceptAnnouncements(boolean on) {
        prefs.edit().putBoolean("acceptAnnouncements", on).apply();
    }

    void setAutoStart(boolean on) {
        prefs.edit().putBoolean("autoStart", on).apply();
    }

    boolean quietEnabled() {
        return prefs.getBoolean("quietEnabled", false);
    }

    int quietDays() {
        return prefs.getInt("quietDays", 127);
    }

    int quietStart() {
        return prefs.getInt("quietStart", 1320);
    }

    int quietEnd() {
        return prefs.getInt("quietEnd", 420);
    }

    void saveQuietHours(
            boolean enabled, int days, int start, int end, Set<String> exceptions) {
        prefs.edit()
                .putBoolean("quietEnabled", enabled)
                .putInt("quietDays", days)
                .putInt("quietStart", start)
                .putInt("quietEnd", end)
                .putStringSet("quietExceptions", new HashSet<>(exceptions))
                .apply();
    }

    void setWidgetTarget(int widgetId, String peerId) {
        prefs.edit().putString("widget:" + widgetId, peerId).apply();
    }

    String widgetTarget(int widgetId) {
        return prefs.getString("widget:" + widgetId, "");
    }

    /** {@link AnnounceWidget#ALL} or a room group's id. */
    void setAnnounceTarget(int widgetId, String target) {
        prefs.edit().putString("announceWidget:" + widgetId, target).apply();
    }

    String announceTarget(int widgetId) {
        return prefs.getString("announceWidget:" + widgetId, "");
    }

    private SecretKey storageKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias("intercom-secret")) {
            KeyGenerator gen =
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(
                    new KeyGenParameterSpec.Builder(
                                    "intercom-secret",
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build());
            gen.generateKey();
        }
        return (SecretKey) ks.getKey("intercom-secret", null);
    }
}
