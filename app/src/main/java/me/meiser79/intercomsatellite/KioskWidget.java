package me.meiser79.intercomsatellite;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home-screen shortcut that calls one kiosk. Colors come from the dynamic system palette via the
 * layout.
 */
public final class KioskWidget extends AppWidgetProvider {
    static void update(Context context, int widgetId) {
        Config config = new Config(context);
        String target = config.widgetTarget(widgetId);
        String name = context.getString(R.string.widget_select);
        boolean video = false;
        JSONArray known = config.known();
        for (int i = 0; i < known.length(); i++) {
            JSONObject p = known.optJSONObject(i);
            if (p != null && target.equals(p.optString("id"))) {
                name = config.peerName(target);
                video = p.optBoolean("video") && config.video();
                break;
            }
        }
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.kiosk_widget);
        view.setTextViewText(R.id.widget_name, name);
        view.setImageViewResource(R.id.widget_icon, RoomIcons.drawable(config.icon(target)));
        Peer.Status status = status(target);
        boolean offline = status != Peer.Status.READY && status != Peer.Status.DND;
        view.setImageViewResource(
                R.id.widget_status,
                status == Peer.Status.READY
                        ? R.drawable.widget_status_online
                        : status == Peer.Status.DND
                                ? R.drawable.widget_status_dnd
                                : R.drawable.widget_status_offline);
        view.setContentDescription(
                R.id.widget_name, name + ", " + context.getString(status.label));
        // Offline fades like a disabled button, but stays tappable: the status can lag a little.
        view.setColorStateList(
                R.id.widget_name,
                "setTextColor",
                offline ? R.color.widget_on_surface_variant : R.color.widget_on_surface);
        view.setInt(
                R.id.widget_call,
                "setBackgroundResource",
                offline ? R.drawable.widget_pill_primary_dim : R.drawable.widget_pill_primary);
        view.setInt(
                R.id.widget_video,
                "setBackgroundResource",
                offline ? R.drawable.widget_pill_container_dim : R.drawable.widget_pill_container);
        view.setInt(R.id.widget_call, "setImageAlpha", offline ? 97 : 255);
        view.setInt(R.id.widget_video, "setImageAlpha", offline ? 97 : 255);
        view.setContentDescription(
                R.id.widget_call, context.getString(R.string.kiosk_call_description, name));
        view.setContentDescription(
                R.id.widget_video,
                context.getString(R.string.kiosk_video_call_description, name));
        PendingIntent call = call(context, widgetId, target, false);
        view.setOnClickPendingIntent(R.id.widget_root, call);
        view.setOnClickPendingIntent(R.id.widget_call, call);
        view.setViewVisibility(R.id.widget_video, video ? View.VISIBLE : View.GONE);
        view.setOnClickPendingIntent(R.id.widget_video, call(context, widgetId, target, true));
        AppWidgetManager.getInstance(context).updateAppWidget(widgetId, view);
    }

    /**
     * What the running intercom last found out about a kiosk: READY, DND or, for anything that
     * keeps a call from getting through, UNREACHABLE. Without the intercom every kiosk is offline.
     */
    static Peer.Status status(String id) {
        IntercomService service = IntercomService.instance;
        Engine e = service == null ? null : service.engine;
        if (e == null || id.isEmpty()) return Peer.Status.UNREACHABLE;
        boolean dnd = false;
        for (Peer p : e.peers.values()) {
            if (!id.equals(p.id)) continue;
            if (p.status == Peer.Status.READY) return Peer.Status.READY;
            if (p.status == Peer.Status.DND) dnd = true;
        }
        return dnd ? Peer.Status.DND : Peer.Status.UNREACHABLE;
    }

    /**
     * Redraws the widgets when a kiosk's status changed since {@code shown}; returns the
     * status now shown.
     */
    static String refreshStatus(Context context, String shown) {
        Config config = new Config(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, KioskWidget.class));
        StringBuilder now = new StringBuilder();
        for (int id : ids) {
            String target = config.widgetTarget(id);
            now.append(target).append('=').append(status(target)).append(';');
        }
        if (!now.toString().equals(shown)) for (int id : ids) update(context, id);
        return now.toString();
    }

    /** Opens the app to call {@code target}; each widget and kind of call needs its own intent. */
    private static PendingIntent call(Context context, int widgetId, String target, boolean video) {
        Intent open =
                new Intent(context, MainActivity.class)
                        .putExtra("quickPeer", target)
                        .putExtra("quickVideo", video)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                context,
                widgetId * 2 + (video ? 1 : 0),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, id);
    }

    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, KioskWidget.class)))
            update(context, id);
        Shortcuts.update(context);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        SharedPreferences.Editor edit = new Config(context).prefs.edit();
        for (int id : ids) edit.remove("widget:" + id);
        edit.apply();
    }
}
