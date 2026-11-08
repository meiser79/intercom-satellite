package me.meiser79.intercomsatellite;

import static org.junit.Assert.*;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Set;

public class SettingsTransferTest {
    @Test
    public void settingsMoveButThisPhoneKeepsItsOwn() throws Exception {
        SharedPreferences from = TestPreferences.create();
        from.edit()
                .putString("id", "phone-a")
                .putString("secret", "encrypted")
                .putString("name", "Kitchen phone")
                .putBoolean("video", false)
                .putInt("quietStart", 1320)
                .putLong("dndUntil", 1234567890123L)
                .putStringSet("quietExceptions", Set.of("door", "garden"))
                .putString("widget:7", "door")
                .putString("history", "[]")
                .putBoolean("setupBattery", true)
                .apply();
        JSONObject file =
                new JSONObject(SettingsTransfer.export(from, "shared key").toString());
        assertEquals("shared key", file.getString("key"));
        assertFalse(file.getJSONObject("settings").has("id"));
        assertFalse(file.getJSONObject("settings").has("secret"));
        assertFalse(file.getJSONObject("settings").has("widget:7"));
        // Permissions belong to each phone: the new one still asks for them.
        assertFalse(file.getJSONObject("settings").has("setupBattery"));

        SharedPreferences to = TestPreferences.create();
        to.edit()
                .putString("id", "phone-b")
                .putBoolean("tls", true)
                .putString("widget:3", "garden")
                .putString("history", "[1]")
                .apply();
        assertEquals("shared key", SettingsTransfer.apply(to, file));
        assertEquals("phone-b", to.getString("id", ""));
        assertEquals("Kitchen phone", to.getString("name", ""));
        assertFalse(to.getBoolean("video", true));
        assertEquals(1320, to.getInt("quietStart", 0));
        assertEquals(1234567890123L, to.getLong("dndUntil", 0));
        assertEquals(Set.of("door", "garden"), to.getStringSet("quietExceptions", null));
        // Replaced, not merged: what the file doesn't have is gone.
        assertFalse(to.contains("tls"));
        assertEquals("garden", to.getString("widget:3", ""));
        assertEquals("[1]", to.getString("history", ""));
        assertFalse(to.contains("widget:7"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void otherFilesAreRefused() throws Exception {
        SettingsTransfer.apply(TestPreferences.create(), new JSONObject("{\"format\":\"x\"}"));
    }
}
