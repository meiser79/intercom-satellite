package me.meiser79.intercomsatellite;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/** Home-screen shortcut that starts an announcement to all kiosks or one room group. */
public final class AnnounceWidget extends AppWidgetProvider {
    /** Target of a widget that announces to every available kiosk. */
    static final String ALL = "*";

    static void update(Context context, int widgetId) {
        Config config = new Config(context);
        String target = config.announceTarget(widgetId);
        String name = title(context, target);
        if (name == null) name = context.getString(R.string.widget_select_group);
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.announce_widget);
        view.setTextViewText(R.id.widget_name, name);
        view.setImageViewResource(
                R.id.widget_icon, ALL.equals(target) ? R.drawable.ic_room_home : R.drawable.ic_group);
        view.setContentDescription(
                R.id.widget_announce, context.getString(R.string.announce_description, name));
        Intent open =
                new Intent(context, MainActivity.class)
                        .putExtra("announce", target)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent announce =
                PendingIntent.getActivity(
                        context,
                        -1 - widgetId,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        view.setOnClickPendingIntent(R.id.widget_root, announce);
        view.setOnClickPendingIntent(R.id.widget_announce, announce);
        AppWidgetManager.getInstance(context).updateAppWidget(widgetId, view);
    }

    /** The name an announcement to {@code target} gets; null when the group no longer exists. */
    static String title(Context context, String target) {
        if (ALL.equals(target)) return context.getString(R.string.announce_title_all);
        RoomGroups.Group group = group(context, target);
        return group == null ? null : group.name;
    }

    static RoomGroups.Group group(Context context, String id) {
        for (RoomGroups.Group g : new RoomGroups(new Config(context).prefs).list())
            if (g.id.equals(id)) return g;
        return null;
    }

    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, AnnounceWidget.class)))
            update(context, id);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, id);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        SharedPreferences.Editor edit = new Config(context).prefs.edit();
        for (int id : ids) edit.remove("announceWidget:" + id);
        edit.apply();
    }
}
