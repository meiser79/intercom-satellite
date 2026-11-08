package me.meiser79.intercomsatellite;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.util.Rational;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Home screen (availability + kiosk list) and the in-call screen, which swap inside the same
 * activity.
 */
public final class MainActivity extends AppCompatActivity
        implements CallPanel.Host, KioskDialogs.Host {
    private static final String EXTRA_ANSWER = "answer";
    private static final String EXTRA_CALL = "call";
    private static final String EXTRA_QUICK_PEER = "quickPeer";
    private static final String EXTRA_QUICK_VIDEO = "quickVideo";
    private static final String EXTRA_ANNOUNCE = "announce";
    /** How long an announcement from the widget waits for rooms still being found. */
    private static final long ANNOUNCE_GATHER_MS = 3000;
    private static final long TICK_MS = 200;
    private static final long QUICK_CALL_WINDOW_MS = 15_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PermissionHelper permissions = new PermissionHelper(this);
    private final Runnable ticker =
            new Runnable() {
                @Override
                public void run() {
                    if (!resumed && !pip) return;
                    refresh();
                    handler.postDelayed(this, TICK_MS);
                }
            };
    private final OnBackPressedCallback endCallOnBack =
            new OnBackPressedCallback(false) {
                @Override
                public void handleOnBackPressed() {
                    confirmEndCall();
                }
            };

    private Config config;
    private KioskDialogs dialogs;
    private CallPanel callPanel;
    private MaterialToolbar toolbar;
    private View homeContent;
    private View callContent;

    private TextView statusTitle, statusHint, dndUntil, textError, emptyTitle;
    private MaterialButton btnPower,
            btnEnable,
            btnSnooze,
            btnRefresh,
            btnAnnounce,
            btnAddKiosk;
    private MaterialSwitch switchDnd;
    private View dndGroup, cardEmpty, actions;
    private LinearLayout kioskList;

    private String fingerprint = "";
    private boolean resumed, pip;
    private View pipFrame;
    private TextureView pipView, pipSelfView;
    private Surface pipSurface;
    private SurfaceTexture pipSelf;
    private String pipParams = "";
    private boolean answerPending;
    /** The setup checklist gets one question per return to the app, once the intercom runs. */
    private boolean checklistPending;
    private boolean quickStarted;
    private String quickPeer = "";
    private boolean quickVideo;
    private long quickUntil, announceSince;
    private String quickAnnounce = "";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        config = new Config(this);
        dialogs = new KioskDialogs(this, config, this);
        setContentView(R.layout.activity_main);
        bindViews();
        Ui.applyBottomInsets(findViewById(R.id.scroll));
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        getOnBackPressedDispatcher().addCallback(this, endCallOnBack);
        handleIntent(getIntent());
        render(engine());
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        toolbar.inflateMenu(R.menu.main_menu);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        pipFrame = findViewById(R.id.pip_frame);
        pipView = findViewById(R.id.pip_video);
        pipSelfView = findViewById(R.id.pip_self);
        pipView.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                        pipSurface = new Surface(t);
                        Engine e = engine();
                        if (e != null) e.video.farSurface(VideoPipe.PIP, pipSurface);
                    }

                    public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                        Engine e = engine();
                        if (e != null) e.video.farSurface(VideoPipe.PIP, null);
                        if (pipSurface != null) pipSurface.release();
                        pipSurface = null;
                        return true;
                    }

                    public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {}

                    public void onSurfaceTextureUpdated(SurfaceTexture t) {}
                });
        pipSelfView.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                        pipSelf = t;
                    }

                    public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                        Engine e = engine();
                        if (e != null) e.video.selfSurface(VideoPipe.PIP, null);
                        pipSelf = null;
                        return true;
                    }

                    public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {}

                    public void onSurfaceTextureUpdated(SurfaceTexture t) {}
                });

        homeContent = findViewById(R.id.home_content);
        callContent = findViewById(R.id.call_content);
        callPanel = new CallPanel(callContent, config, this);

        statusTitle = homeContent.findViewById(R.id.status_title);
        statusHint = homeContent.findViewById(R.id.status_hint);
        btnPower = homeContent.findViewById(R.id.btn_power);
        btnEnable = homeContent.findViewById(R.id.btn_enable);
        dndGroup = homeContent.findViewById(R.id.dnd_group);
        switchDnd = homeContent.findViewById(R.id.switch_dnd);
        dndUntil = homeContent.findViewById(R.id.dnd_until);
        btnSnooze = homeContent.findViewById(R.id.btn_snooze);
        textError = homeContent.findViewById(R.id.text_error);
        btnRefresh = homeContent.findViewById(R.id.btn_refresh);
        kioskList = homeContent.findViewById(R.id.kiosk_list);
        cardEmpty = homeContent.findViewById(R.id.card_empty);
        emptyTitle = homeContent.findViewById(R.id.empty_title);
        actions = homeContent.findViewById(R.id.actions);
        btnAnnounce = homeContent.findViewById(R.id.btn_announce);
        btnAddKiosk = homeContent.findViewById(R.id.btn_add_kiosk);

        btnPower.setOnClickListener(v -> IntercomControl.stop(this));
        btnSnooze.setOnClickListener(v -> dialogs.snooze());
        btnRefresh.setOnClickListener(
                v -> {
                    Engine e = engine();
                    if (e != null) e.refresh();
                });
        btnAnnounce.setOnClickListener(v -> dialogs.chooseAnnouncement());
        btnAddKiosk.setOnClickListener(v -> dialogs.addPeer());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        invalidate();
    }

    private void handleIntent(Intent intent) {
        answerPending = intent.getBooleanExtra(EXTRA_ANSWER, false);
        // A ringing call: over the lock screen and with the display on right away. render() sets
        // these too, but an activity that was already open, stopped behind the lock screen, would
        // never resume to render.
        if (intent.hasExtra(EXTRA_CALL)) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        String wanted = intent.getStringExtra(EXTRA_QUICK_PEER);
        if (wanted != null && !wanted.isEmpty()) {
            quickPeer = wanted;
            quickVideo = intent.getBooleanExtra(EXTRA_QUICK_VIDEO, false);
            quickUntil = System.currentTimeMillis() + QUICK_CALL_WINDOW_MS;
            quickStarted = false;
            // Lets the launcher rank the kiosks called most often first.
            ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
            if (shortcuts != null) shortcuts.reportShortcutUsed("kiosk:" + wanted);
        }
        String announce = intent.getStringExtra(EXTRA_ANNOUNCE);
        if (announce != null && !announce.isEmpty()) {
            quickPeer = "";
            quickAnnounce = announce;
            announceSince = System.currentTimeMillis();
            quickUntil = announceSince + QUICK_CALL_WINDOW_MS;
            quickStarted = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        KioskWidget.refreshAll(this);
        PhoneIntegration.register(this);
        checklistPending = true;
        invalidate();
        resumed = true;
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(ticker);
        Engine e = engine();
        if (e != null && !e.handsFree) e.talk(false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        // The camera only sends while a call screen is visible; turning the phone keeps it on, and
        // in a call the phone app shows, that app's screen decides.
        Engine e = engine();
        if (e != null && e.cameraOn && !e.phoneManaged && !isChangingConfigurations())
            e.camera(false);
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        // Turned or resized here rather than recreated: the kiosk tiles take the new width.
        fingerprint = "";
        refresh();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        pip = inPip;
        pipFrame.setVisibility(inPip ? View.VISIBLE : View.GONE);
        if (inPip) handler.post(ticker);
        else {
            // A hidden TextureView keeps its surface, which would keep taking the kiosk's
            // picture from the call screen.
            Engine e = engine();
            if (e != null) {
                e.video.farSurface(VideoPipe.PIP, null);
                e.video.selfSurface(VideoPipe.PIP, null);
            }
            invalidate();
        }
    }

    /**
     * Leaving the app during a call with video shrinks it to a picture-in-picture window instead
     * of hiding it, so the camera keeps sending; the window shows the kiosk's picture, or this
     * phone's camera without one, and closes when the call ends.
     */
    private void syncPip(Engine e) {
        boolean shown = e != null && e.active() && !e.phoneManaged;
        boolean far = shown && e.farCamera && e.video.farShown;
        boolean self = shown && !far && e.cameraOn;
        boolean video = far || self;
        if (pip && !video) {
            moveTaskToBack(false);
            return;
        }
        if (pip) {
            pipView.setVisibility(far ? View.VISIBLE : View.INVISIBLE);
            pipSelfView.setVisibility(self ? View.VISIBLE : View.INVISIBLE);
            if (pipSurface != null) e.video.farSurface(VideoPipe.PIP, pipSurface);
            e.video.selfSurface(VideoPipe.PIP, self ? pipSelf : null);
            return;
        }
        int width = far ? e.video.farWidth : self ? e.video.selfWidth : 0;
        int height = far ? e.video.farHeight : self ? e.video.selfHeight : 0;
        // The window grows out of the picture on the call screen.
        Rect from = far ? callPanel.farBounds() : self ? callPanel.selfBounds() : null;
        String key = video + ":" + width + "x" + height + ":" + from;
        if (key.equals(pipParams)) return;
        pipParams = key;
        PictureInPictureParams.Builder params =
                new PictureInPictureParams.Builder()
                        .setAutoEnterEnabled(video)
                        .setSourceRectHint(from);
        // Android takes aspect ratios between 1:2.39 and 2.39:1.
        if (width > 0 && height > 0 && width * 100 <= height * 239 && height * 100 <= width * 239)
            params.setAspectRatio(new Rational(width, height));
        setPictureInPictureParams(params.build());
    }

    @Override
    public Engine engine() {
        IntercomService service = IntercomService.instance;
        return service == null ? null : service.engine;
    }

    @Override
    public void invalidate() {
        fingerprint = "";
    }

    // ---------------------------------------------------------------- polling

    private void refresh() {
        Engine e = engine();
        String current = fingerprint(e);
        if (!current.equals(fingerprint)) {
            fingerprint = current;
            render(e);
        }
        if (e != null && e.active()) callPanel.tick(e);
        syncPip(e);
        if (answerPending
                && e != null
                && e.state.equals("ringing")
                && e.callId.equals(getIntent().getStringExtra(EXTRA_CALL))) {
            answerPending = false;
            acceptCall();
        }
        if (!quickPeer.isEmpty()) continueQuickCall(e);
        if (!quickAnnounce.isEmpty()) continueQuickAnnouncement(e);
    }

    /**
     * Everything that changes what is on screen; the UI is only rebuilt when this string changes.
     */
    private String fingerprint(Engine e) {
        if (e == null) return "off" + IntercomService.lastError;
        StringBuilder f = new StringBuilder();
        f.append(e.state)
                .append(e.callId)
                .append(e.detail)
                .append(e.phoneManaged)
                .append(e.broadcast)
                .append(e.announcementTitle)
                .append(e.audio.microphoneAvailable)
                .append(e.peerVideo)
                .append(e.cameraOn)
                .append(e.farCamera)
                .append(e.video.farShown)
                .append(e.video.selfWidth)
                .append(e.video.farWidth)
                .append(e.video.farHeight)
                .append(e.cameraReady())
                .append(config.dnd())
                .append(config.dndUntil())
                .append(config.androidDnd())
                .append(config.quiet())
                .append(IntercomService.lastError);
        for (Peer p : Peers.sorted(e, config)) {
            f.append(p.name)
                    .append(p.status)
                    .append(p.ready)
                    .append(p.video)
                    .append(p.host)
                    .append(p.candidatePin)
                    .append(config.alias(p.id))
                    .append(config.favorite(p.id));
        }
        if (e.peer != null) f.append(e.peer.candidatePin);
        return f.toString();
    }

    // ---------------------------------------------------------------- rendering

    private void render(Engine e) {
        boolean calling = e != null && e.active();
        homeContent.setVisibility(calling ? View.GONE : View.VISIBLE);
        callContent.setVisibility(calling ? View.VISIBLE : View.GONE);
        toolbar.getMenu().setGroupVisible(R.id.group_main, !calling);
        endCallOnBack.setEnabled(calling);
        // Only a call may appear above the lock screen or wake the display. Otherwise the app
        // would stay on top of the keyguard whenever it was open while the phone locked.
        setShowWhenLocked(calling);
        setTurnScreenOn(calling && e.state.equals("ringing"));
        if (calling) callPanel.render(e);
        else renderHome(e);
        // Not while a widget, shortcut or notification is about to start something.
        if (checklistPending
                && resumed
                && e != null
                && !calling
                && !answerPending
                && quickPeer.isEmpty()
                && quickAnnounce.isEmpty()) {
            checklistPending = false;
            SetupChecklist.next(this, config, permissions);
        }
    }

    private void renderHome(Engine e) {
        boolean on = e != null;
        renderAvailability(e);

        showText(textError, IntercomService.lastError);

        btnRefresh.setVisibility(on ? View.VISIBLE : View.GONE);
        actions.setVisibility(on ? View.VISIBLE : View.GONE);
        btnAnnounce.setEnabled(on && Peers.anyReady(e));

        kioskList.removeAllViews();
        boolean hasPeers = on && !e.peers.isEmpty();
        cardEmpty.setVisibility(hasPeers ? View.GONE : View.VISIBLE);
        emptyTitle.setText(on ? R.string.empty_searching_title : R.string.empty_off_title);
        if (hasPeers) {
            // Tiles side by side, two to a row on a phone and three on a wider screen or turned.
            int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
            int gap = Math.round(8 * getResources().getDisplayMetrics().density);
            LinearLayout row = null;
            int inRow = 0;
            for (Peer p : Peers.sorted(e, config)) {
                if (inRow == columns) row = null;
                if (row == null) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams lp =
                            new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = gap;
                    kioskList.addView(row, lp);
                    inRow = 0;
                }
                View tile = createKioskRow(e, p, row);
                if (inRow > 0)
                    ((ViewGroup.MarginLayoutParams) tile.getLayoutParams()).setMarginStart(gap);
                row.addView(tile);
                inRow++;
            }
            // An unfilled last row keeps its tiles as wide as the others. The filler matches the
            // row's height like the tiles: a row with any other child would take that child's
            // height and leave the tiles, which match the row, at none.
            for (; row != null && inRow < columns; inRow++) {
                Space space = new Space(this);
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.setMarginStart(gap);
                row.addView(space, lp);
            }
        }
    }

    private void renderAvailability(Engine e) {
        boolean on = e != null;
        int title =
                !on
                        ? R.string.status_offline
                        : config.dnd()
                                ? R.string.status_dnd
                                : config.androidDnd()
                                        ? R.string.status_android_dnd
                                        : config.quiet()
                                                ? R.string.status_quiet
                                                : R.string.status_online;
        statusTitle.setText(title);
        statusTitle.setTextColor(on ? Ui.primary(this) : Ui.onSurfaceVariant(this));
        statusHint.setText(
                on
                        ? getString(R.string.status_online_hint, config.name(), localAddress())
                        : getString(R.string.status_offline_hint));

        btnPower.setVisibility(on ? View.VISIBLE : View.GONE);
        boolean needsKey = config.key().isEmpty();
        btnEnable.setVisibility(on ? View.GONE : View.VISIBLE);
        btnEnable.setText(needsKey ? R.string.action_setup : R.string.action_enable);
        btnEnable.setOnClickListener(
                v -> {
                    if (needsKey) openSettings();
                    else IntercomControl.enable(this, permissions);
                });

        dndGroup.setVisibility(on ? View.VISIBLE : View.GONE);
        CompoundButton.OnCheckedChangeListener dndListener =
                (button, checked) -> {
                    config.setDnd(checked);
                    Engine current = engine();
                    if (current != null) current.changed();
                    invalidate();
                };
        switchDnd.setOnCheckedChangeListener(null);
        switchDnd.setChecked(config.dnd());
        switchDnd.setOnCheckedChangeListener(dndListener);
        if (config.dnd()) {
            long until = config.dndUntil();
            dndUntil.setText(
                    until == 0
                            ? getString(R.string.dnd_until_manual)
                            : getString(
                                    R.string.dnd_until_time,
                                    DateFormat.getDateTimeInstance(
                                                    DateFormat.SHORT, DateFormat.SHORT)
                                            .format(new Date(until))));
            dndUntil.setVisibility(View.VISIBLE);
        } else {
            dndUntil.setVisibility(View.GONE);
        }
    }

    private String localAddress() {
        String ip = Discovery.localIp();
        return ip.isEmpty() ? getString(R.string.no_local_network) : ip;
    }

    private static void showText(TextView view, String text) {
        view.setText(text);
        view.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View createKioskRow(Engine e, Peer p, ViewGroup parent) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_kiosk, parent, false);
        ((ImageView) row.findViewById(R.id.kiosk_glyph))
                .setImageResource(RoomIcons.drawable(config.icon(p.id)));
        ((TextView) row.findViewById(R.id.kiosk_name)).setText(config.display(p));
        row.findViewById(R.id.kiosk_favorite)
                .setVisibility(config.favorite(p.id) ? View.VISIBLE : View.GONE);
        // Online, Do not disturb or not reachable; the info button below explains the reason.
        ImageView badge = row.findViewById(R.id.kiosk_badge);
        badge.setImageResource(
                p.ready
                        ? R.drawable.status_online
                        : p.status == Peer.Status.DND
                                ? R.drawable.status_dnd
                                : R.drawable.status_offline);
        badge.setContentDescription(getString(p.status.label));
        ((TextView) row.findViewById(R.id.kiosk_host)).setText(p.host);

        MaterialButton call = row.findViewById(R.id.kiosk_call);
        MaterialButton video = row.findViewById(R.id.kiosk_video);
        MaterialButton info = row.findViewById(R.id.kiosk_info);
        call.setVisibility(p.ready ? View.VISIBLE : View.GONE);
        call.setContentDescription(getString(R.string.kiosk_call_description, config.display(p)));
        boolean videoReady = p.ready && p.candidatePin.isEmpty() && p.video && e.cameraReady();
        video.setVisibility(videoReady ? View.VISIBLE : View.GONE);
        video.setContentDescription(getString(R.string.kiosk_video_call_description, config.display(p)));
        video.setOnClickListener(v -> callPeer(e, p, true));
        info.setVisibility(p.ready ? View.GONE : View.VISIBLE);
        info.setText(p.candidatePin.isEmpty() ? R.string.kiosk_info : R.string.kiosk_verify);
        call.setOnClickListener(
                v -> {
                    if (!p.candidatePin.isEmpty()) dialogs.trust(p);
                    else callPeer(e, p);
                });
        info.setOnClickListener(
                v -> {
                    if (!p.candidatePin.isEmpty()) dialogs.trust(p);
                    else dialogs.explain(p);
                });
        row.findViewById(R.id.kiosk_more).setOnClickListener(v -> dialogs.roomOptions(e, p));
        return row;
    }

    // ---------------------------------------------------------------- actions

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_history) startActivity(new Intent(this, HistoryActivity.class));
        else if (id == R.id.action_groups) startActivity(new Intent(this, GroupsActivity.class));
        else if (id == R.id.action_settings) openSettings();
        else return false;
        return true;
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void callPeer(Engine e, Peer p) {
        callPeer(e, p, false);
    }

    /** Calls a kiosk; with video the camera is asked for too, and a refusal calls voice only. */
    private void callPeer(Engine e, Peer p, boolean withVideo) {
        permissions.requireMicrophone(
                () -> {
                    Runnable call =
                            () -> {
                                IntercomService service = IntercomService.instance;
                                if (service == null) return;
                                service.microphone();
                                e.call(p, withVideo && permissions.has(Manifest.permission.CAMERA));
                            };
                    if (withVideo) withCamera(call);
                    else call.run();
                });
    }

    /** Asks for the camera, then runs {@code action} either way (it checks the grant itself). */
    private void withCamera(Runnable action) {
        permissions.require(
                Manifest.permission.CAMERA, R.string.toast_camera_permission_denied, true, action);
    }

    @Override
    public void acceptCall() {
        Engine current = engine();
        if (current == null || current.phoneManaged) return;
        String expected = current.callId;
        permissions.requireMicrophone(
                () -> {
                    IntercomService service = IntercomService.instance;
                    if (service == null || service.engine == null) return;
                    Engine e = service.engine;
                    if (e.callId.equals(expected) && !e.phoneManaged) {
                        service.microphone();
                        e.answer();
                    }
                });
    }

    @Override
    public void acceptVideoCall() {
        Engine current = engine();
        if (current == null || current.phoneManaged) return;
        String expected = current.callId;
        permissions.requireMicrophone(
                () ->
                        withCamera(
                                () -> {
                                    IntercomService service = IntercomService.instance;
                                    if (service == null || service.engine == null) return;
                                    Engine e = service.engine;
                                    if (e.callId.equals(expected) && !e.phoneManaged) {
                                        service.microphone();
                                        e.answer(permissions.has(Manifest.permission.CAMERA));
                                    }
                                }));
    }

    @Override
    public void toggleCamera() {
        Engine current = engine();
        if (current == null) return;
        if (current.cameraOn) {
            current.camera(false);
            return;
        }
        String expected = current.callId;
        permissions.require(
                Manifest.permission.CAMERA,
                R.string.toast_camera_permission_denied,
                false,
                () -> {
                    Engine e = engine();
                    if (e == null || !e.callId.equals(expected)) return;
                    if (!e.camera(true)) Ui.toast(this, R.string.toast_camera_unavailable);
                });
    }

    @Override
    public void trust(Peer peer) {
        dialogs.trust(peer);
    }

    @Override
    public void startAnnouncement(Set<String> members, String title) {
        permissions.requireMicrophone(
                () -> {
                    IntercomService service = IntercomService.instance;
                    if (service == null || service.engine == null) {
                        Ui.toast(this, R.string.toast_enable_intercom);
                        return;
                    }
                    Engine e = service.engine;
                    if (e.active()) {
                        Ui.toast(this, R.string.toast_finish_call_first);
                        return;
                    }
                    service.microphone();
                    e.announce(
                            members == null ? null : new LinkedHashSet<>(members), title);
                    if (!e.active()) e.changed();
                });
    }

    private void confirmEndCall() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.end_call_confirm)
                .setNegativeButton(R.string.keep_talking, null)
                .setPositiveButton(
                        R.string.end,
                        (d, w) -> {
                            Engine e = engine();
                            if (e != null) e.finish(getString(R.string.msg_call_ended), true);
                        })
                .show();
    }

    // ---------------------------------------------------------------- widget / history quick call

    /**
     * Calls the kiosk requested by a widget or the call history once it is reachable (max. 15 s).
     */
    private void continueQuickCall(Engine e) {
        if (System.currentTimeMillis() > quickUntil) {
            quickPeer = "";
            Ui.toast(this, R.string.toast_peer_unreachable);
            return;
        }
        if (e == null) {
            if (!quickStarted && !config.key().isEmpty()) {
                quickStarted = true;
                IntercomControl.enable(this, permissions);
            }
            return;
        }
        if (e.active()) {
            quickPeer = "";
            Ui.toast(this, R.string.toast_finish_call_first);
            return;
        }
        for (Peer p : e.peers.values()) {
            if (quickPeer.equals(p.id) && p.ready) {
                quickPeer = "";
                // A kiosk without video, or a phone without a camera, gets a voice call.
                callPeer(e, p, quickVideo && p.video && p.candidatePin.isEmpty() && e.cameraReady());
                return;
            }
        }
        JSONObject known = findKnownPeer(quickPeer);
        if (known == null) {
            quickPeer = "";
            Ui.toast(this, R.string.toast_peer_not_saved);
            return;
        }
        try {
            probe(e, known);
        } catch (Exception ex) {
            quickPeer = "";
            Ui.toast(this, R.string.toast_peer_address_invalid);
        }
    }

    /** Looks for a saved kiosk at its last address, unless that is already under way. */
    private static void probe(Engine e, JSONObject known) throws Exception {
        String host = known.optString("host");
        int port = known.optInt("port", 2324);
        for (Peer p : e.peers.values()) {
            if (host.equals(p.host) && port == p.adminPort) return;
        }
        e.add((known.optBoolean("tls") ? "https://" : "http://") + host + ":" + port);
    }

    /**
     * Starts the announcement requested by a widget once its rooms are reachable: right away
     * when all are, otherwise a moment after the first one, so rooms still being found join.
     */
    private void continueQuickAnnouncement(Engine e) {
        long now = System.currentTimeMillis();
        if (now > quickUntil) {
            quickAnnounce = "";
            Ui.toast(this, R.string.toast_no_peer_ready);
            return;
        }
        if (e == null) {
            if (!quickStarted && !config.key().isEmpty()) {
                quickStarted = true;
                IntercomControl.enable(this, permissions);
            }
            return;
        }
        if (e.active()) {
            quickAnnounce = "";
            Ui.toast(this, R.string.toast_finish_call_first);
            return;
        }
        Set<String> members = null;
        String title = AnnounceWidget.title(this, quickAnnounce);
        if (title == null) {
            quickAnnounce = "";
            Ui.toast(this, R.string.toast_group_missing);
            return;
        }
        Set<String> wanted = new LinkedHashSet<>();
        if (AnnounceWidget.ALL.equals(quickAnnounce)) {
            JSONArray known = config.known();
            for (int i = 0; i < known.length(); i++) {
                JSONObject item = known.optJSONObject(i);
                if (item != null && !item.optString("id").isEmpty()) wanted.add(item.optString("id"));
            }
        } else {
            members = AnnounceWidget.group(this, quickAnnounce).members;
            wanted.addAll(members);
        }
        Set<String> ready = new HashSet<>();
        for (Peer p : e.peers.values())
            if (p.ready && (members == null || members.contains(p.id))) ready.add(p.id);
        if (ready.isEmpty() || (!ready.containsAll(wanted) && now - announceSince < ANNOUNCE_GATHER_MS)) {
            for (String id : wanted) {
                JSONObject known = findKnownPeer(id);
                if (known == null || ready.contains(id)) continue;
                try {
                    probe(e, known);
                } catch (Exception ignored) {
                }
            }
            return;
        }
        quickAnnounce = "";
        startAnnouncement(members, title);
    }

    private JSONObject findKnownPeer(String id) {
        JSONArray items = config.known();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }
}
