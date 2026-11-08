package me.meiser79.intercomsatellite;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Settings as a file, to move them to another phone or keep a copy. The intercom key goes along
 * in plain text, since the stored one is encrypted with a key that never leaves this phone.
 * What belongs to this phone alone stays: its ID, the call history, widgets, shortcuts and which
 * setup questions it asked, since its permissions are its own.
 */
final class SettingsTransfer {
    static final String FORMAT = "intercom-satellite-settings";
    static final int VERSION = 1;

    private SettingsTransfer() {}

    /** Kept on import and left out of an export. */
    static boolean local(String key) {
        return switch (key) {
            case "id", "secret", "installed", "enabledBefore", "shortcuts", "history" -> true;
            default ->
                    key.startsWith("widget:")
                            || key.startsWith("announceWidget:")
                            || key.startsWith("setup");
        };
    }

    static JSONObject export(SharedPreferences prefs, String intercomKey) throws Exception {
        JSONObject settings = new JSONObject();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (local(e.getKey()) || e.getValue() == null) continue;
            Object v = e.getValue();
            String type =
                    v instanceof Boolean
                            ? "boolean"
                            : v instanceof Integer
                                    ? "int"
                                    : v instanceof Long
                                            ? "long"
                                            : v instanceof Float
                                                    ? "float"
                                                    : v instanceof Set ? "set" : "string";
            Object value = v instanceof Set<?> set ? new JSONArray(set) : v;
            settings.put(e.getKey(), Wire.obj("type", type, "value", value));
        }
        return Wire.obj(
                "format", FORMAT,
                "version", VERSION,
                "key", intercomKey,
                "settings", settings);
    }

    /**
     * Replaces this phone's settings with the file's, except what is {@link #local}. Returns the
     * intercom key, which the caller stores encrypted.
     */
    static String apply(SharedPreferences prefs, JSONObject file) throws Exception {
        if (!FORMAT.equals(file.optString("format")) || file.optInt("version") != VERSION)
            throw new IllegalArgumentException("Not a settings file");
        String key = file.optString("key").trim();
        if (key.isEmpty()) throw new IllegalArgumentException("No intercom key");
        JSONObject settings = file.getJSONObject("settings");
        SharedPreferences.Editor edit = prefs.edit();
        for (String name : prefs.getAll().keySet()) if (!local(name)) edit.remove(name);
        for (Iterator<String> it = settings.keys(); it.hasNext(); ) {
            String name = it.next();
            if (local(name)) continue;
            JSONObject entry = settings.getJSONObject(name);
            switch (entry.getString("type")) {
                case "boolean" -> edit.putBoolean(name, entry.getBoolean("value"));
                case "int" -> edit.putInt(name, entry.getInt("value"));
                case "long" -> edit.putLong(name, entry.getLong("value"));
                case "float" -> edit.putFloat(name, (float) entry.getDouble("value"));
                case "set" -> {
                    JSONArray a = entry.getJSONArray("value");
                    Set<String> set = new HashSet<>();
                    for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                    edit.putStringSet(name, set);
                }
                default -> edit.putString(name, entry.getString("value"));
            }
        }
        edit.commit();
        return key;
    }
}
