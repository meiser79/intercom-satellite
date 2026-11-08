package me.meiser79.intercomsatellite;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Widget configuration: pick the kiosk a kiosk widget calls, or whom an announcement widget
 * speaks to. Shown as a dialog over a transparent window.
 */
public final class WidgetSetupActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        int widgetId =
                getIntent()
                        .getIntExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID,
                                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        Config config = new Config(this);
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId);
        if (info != null && info.provider.getClassName().equals(AnnounceWidget.class.getName())) {
            chooseAnnouncement(config, widgetId);
            return;
        }
        JSONArray known = config.known();
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < known.length(); i++) {
            JSONObject p = known.optJSONObject(i);
            if (p == null || p.optString("id").isEmpty()) continue;
            ids.add(p.optString("id"));
            names.add(config.peerName(p.optString("id")));
        }
        if (ids.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.widget_title)
                    .setMessage(R.string.widget_no_kiosk)
                    .setPositiveButton(R.string.ok, (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.widget_pick_kiosk)
                .setItems(
                        names.toArray(new String[0]),
                        (d, index) -> {
                            config.setWidgetTarget(widgetId, ids.get(index));
                            KioskWidget.update(this, widgetId);
                            done(widgetId);
                        })
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    /** Announcement widget: all kiosks or one of the saved room groups. */
    private void chooseAnnouncement(Config config, int widgetId) {
        ArrayList<String> targets = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        targets.add(AnnounceWidget.ALL);
        labels.add(getString(R.string.announce_all));
        for (RoomGroups.Group group : new RoomGroups(config.prefs).list()) {
            targets.add(group.id);
            labels.add(
                    getResources()
                            .getQuantityString(
                                    R.plurals.announce_group_label,
                                    group.members.size(),
                                    group.name,
                                    group.members.size()));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.announce_to)
                .setItems(
                        labels.toArray(new String[0]),
                        (d, index) -> {
                            config.setAnnounceTarget(widgetId, targets.get(index));
                            AnnounceWidget.update(this, widgetId);
                            done(widgetId);
                        })
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void done(int widgetId) {
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        finish();
    }
}
