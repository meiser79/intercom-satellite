package me.meiser79.intercomsatellite;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** A Quick Settings tile that turns the intercom's Do not disturb on and off. */
public final class DndTileService extends TileService {
    @Override
    public void onStartListening() {
        show();
    }

    @Override
    public void onClick() {
        Config config = new Config(this);
        config.setDnd(!config.dnd());
        IntercomService service = IntercomService.instance;
        Engine e = service == null ? null : service.engine;
        if (e != null) e.changed();
        show();
    }

    private void show() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(new Config(this).dnd() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(getString(R.string.app_name));
        tile.updateTile();
    }

    /** Lets an open Quick Settings panel show a change made in the app. */
    static void refresh(Context context) {
        TileService.requestListeningState(
                context, new ComponentName(context, DndTileService.class));
    }
}
