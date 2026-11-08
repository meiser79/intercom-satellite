package me.meiser79.intercomsatellite;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import androidx.appcompat.content.res.AppCompatResources;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Long-pressing the app icon offers calls to saved kiosks, favorites first. The list is only
 * handed to Android when it changes, since Android limits how often a background app may do so.
 */
final class Shortcuts {
    private static final int MAX = 4;

    private Shortcuts() {}

    static void update(Context context) {
        Config config = new Config(context);
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        JSONArray known = config.known();
        List<String> ids = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < known.length() && ids.size() < MAX; i++) {
                String id = known.optJSONObject(i) == null ? "" : known.optJSONObject(i).optString("id");
                if (id.isEmpty() || ids.contains(id) || config.favorite(id) != (pass == 0)) continue;
                ids.add(id);
            }
        StringBuilder signature = new StringBuilder();
        for (String id : ids)
            signature.append(id).append('|').append(config.peerName(id)).append('|')
                    .append(config.icon(id)).append('\n');
        if (signature.toString().equals(config.prefs.getString("shortcuts", null))) return;
        List<ShortcutInfo> shortcuts = new ArrayList<>();
        int rank = 0;
        for (String id : ids) {
            String name = config.peerName(id);
            shortcuts.add(
                    new ShortcutInfo.Builder(context, "kiosk:" + id)
                            .setShortLabel(name)
                            .setLongLabel(context.getString(R.string.kiosk_call_description, name))
                            .setIcon(icon(context, RoomIcons.drawable(config.icon(id))))
                            .setRank(rank++)
                            .setIntent(
                                    new Intent(context, MainActivity.class)
                                            .setAction(Intent.ACTION_VIEW)
                                            .putExtra("quickPeer", id)
                                            .addFlags(
                                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            .build());
        }
        try {
            manager.setDynamicShortcuts(shortcuts);
            config.prefs.edit().putString("shortcuts", signature.toString()).apply();
        } catch (IllegalStateException rateLimited) {
            // Tried again on the next change or when the app opens.
        }
    }

    /** The room icon on the system's accent color, shaped like the launcher's other icons. */
    private static Icon icon(Context context, int drawable) {
        int size = Math.round(108 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(context.getColor(android.R.color.system_accent1_100));
        Drawable glyph = AppCompatResources.getDrawable(context, drawable).mutate();
        glyph.setTint(context.getColor(android.R.color.system_accent1_700));
        int inset = size * 3 / 10;
        glyph.setBounds(inset, inset, size - inset, size - inset);
        glyph.draw(canvas);
        return Icon.createWithAdaptiveBitmap(bitmap);
    }
}
