package me.meiser79.intercomsatellite;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import android.view.KeyEvent;

public final class IntercomService extends Service {
    static volatile IntercomService instance;
    volatile Engine engine;
    PhoneIntegration phone;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake, proximity, screen;
    private MediaSession buttons;
    private Vibrator vibrator;
    private WifiManager.WifiLock wifiLock;
    private Ringtone ring;
    private String ringingId = "";
    private int missedShown;
    private Boolean dndShown;
    private String widgetStatus = "";
    private boolean microphone;
    private String availability = "";
    private final Runnable availabilityTick =
            new Runnable() {
                public void run() {
                    Engine current = engine;
                    if (current != null) {
                        String next =
                                current.config.dnd()
                                        + ":"
                                        + current.config.androidDnd()
                                        + ":"
                                        + current.config.quiet();
                        if (!next.equals(availability)) {
                            availability = next;
                            update();
                        }
                    }
                    main.postDelayed(this, 5000);
                }
            };
    static volatile String lastError = "";
    static final String ENABLE = "enable", STOP = "stop", DECLINE = "decline", RELOAD = "reload";

    public void onCreate() {
        super.onCreate();
        instance = this;
        phone = new PhoneIntegration(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel active =
                new NotificationChannel(
                        "active",
                        getString(R.string.channel_active),
                        NotificationManager.IMPORTANCE_LOW);
        active.setDescription(getString(R.string.channel_active_description));
        nm.createNotificationChannel(active);
        NotificationChannel incoming =
                new NotificationChannel(
                        "calls",
                        getString(R.string.channel_calls),
                        NotificationManager.IMPORTANCE_HIGH);
        incoming.setSound(null, null);
        incoming.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(incoming);
        nm.createNotificationChannel(
                new NotificationChannel(
                        "missed",
                        getString(R.string.channel_missed),
                        NotificationManager.IMPORTANCE_DEFAULT));
        startForeground(
                1,
                ongoing(getString(R.string.notif_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        main.post(availabilityTick);
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ENABLE : intent.getAction();
        if (STOP.equals(action)) {
            new Config(this).prefs.edit().putBoolean("enabledBefore", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (DECLINE.equals(action)) {
            if (engine != null && engine.callId.equals(intent.getStringExtra("call")))
                engine.finish(getString(R.string.msg_call_declined), true);
            return START_NOT_STICKY;
        }
        if (RELOAD.equals(action) && engine != null) {
            engine.stop();
            phone.close();
            engine = null;
        }
        if (engine == null) {
            try {
                Config config = new Config(this);
                if (config.key().isEmpty())
                    throw new Exception(getString(R.string.err_key_missing));
                engine = new Engine(this, config, () -> main.post(this::update));
                engine.start();
                lastError = "";
                config.prefs.edit().putBoolean("enabledBefore", true).apply();
                if (wake == null) {
                    wake =
                            getSystemService(PowerManager.class)
                                    .newWakeLock(
                                            PowerManager.PARTIAL_WAKE_LOCK,
                                            "IntercomSatellite:reachable");
                    wake.acquire();
                }
                WifiManager wifi =
                        (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifi != null && wifiLock == null) {
                    wifiLock =
                            wifi.createWifiLock(
                                    WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IntercomSatellite:WiFi");
                    wifiLock.acquire();
                }
            } catch (Exception e) {
                lastError = getString(R.string.toast_start_failed, e.getMessage());
                stopSelf();
            }
        }
        update();
        return START_NOT_STICKY;
    }

    void microphone() {
        startForeground(
                1,
                ongoing(getString(R.string.notif_call_active)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        microphone = true;
    }

    void phoneMicrophone() {
        int types =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        // The phone app is in front, not this app: the camera needs its own service type.
        Engine e = engine;
        if (e != null && e.peerVideo && e.video.cameraAllowed())
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        startForeground(1, ongoing(getString(R.string.notif_phone_call_active)), types);
        microphone = true;
    }

    private Notification ongoing(String text) {
        PendingIntent open =
                PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop =
                PendingIntent.getService(
                        this,
                        10,
                        new Intent(this, IntercomService.class).setAction(STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "active")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_turn_off), stop).build())
                .build();
    }

    void update() {
        if (instance != this) return;
        Engine e = engine;
        if (e == null) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        phone.sync(e);
        boolean ringing = e.state.equals("ringing") && !e.phoneManaged;
        if (ringing && !ringingId.equals(e.callId)) {
            stopRing();
            ringingId = e.callId;
            try {
                Uri tone = Ringtones.forCall(e.config, e.peer == null ? null : e.peer.id);
                ring = tone == null ? null : RingtoneManager.getRingtone(this, tone);
                if (ring != null) {
                    ring.setLooping(true);
                    ring.play();
                }
            } catch (Exception ignored) {
            }
            vibrate();
            wakeScreen();
            int request = e.callId.hashCode() & 0x3fffffff;
            Intent show =
                    new Intent(this, MainActivity.class)
                            .putExtra("call", e.callId)
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open =
                    PendingIntent.getActivity(
                            this,
                            request,
                            show,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent answer =
                    PendingIntent.getActivity(
                            this,
                            request + 1,
                            new Intent(show).putExtra("answer", true),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent reject =
                    PendingIntent.getService(
                            this,
                            request + 2,
                            new Intent(this, IntercomService.class)
                                    .setAction(DECLINE)
                                    .putExtra("call", e.callId),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Person caller =
                    new Person.Builder()
                            .setName(
                                    e.peer == null
                                            ? getString(R.string.fallback_kiosk)
                                            : e.config.display(e.peer))
                            .setImportant(true)
                            .build();
            Notification n =
                    new Notification.Builder(this, "calls")
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle(caller.getName())
                            .setContentText(getString(R.string.notif_incoming_call))
                            .setCategory(Notification.CATEGORY_CALL)
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .setContentIntent(open)
                            .setFullScreenIntent(open, true)
                            .setOngoing(true)
                            .setStyle(
                                    Notification.CallStyle.forIncomingCall(caller, reject, answer))
                            .build();
            nm.notify(2, n);
        } else if (!ringing) {
            stopRing();
            nm.cancel(2);
        }
        if (e.missed != missedShown) {
            missedShown = e.missed;
            Peer p = e.missedPeer;
            if (p != null) notifyMissed(e, p);
        }
        boolean dnd = e.config.dnd();
        if (!Boolean.valueOf(dnd).equals(dndShown)) {
            dndShown = dnd;
            DndTileService.refresh(this);
        }
        // Not during calls, when video and audio report changes many times a second.
        if (!e.active()) widgetStatus = KioskWidget.refreshStatus(this, widgetStatus);
        syncProximity(e);
        syncButtons(e);
        if (!e.active() && microphone) {
            startForeground(
                    1,
                    ongoing(getString(R.string.notif_reachable)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            microphone = false;
        } else
            nm.notify(1, ongoing(ongoingText(e)));
    }

    /** One notice per kiosk: a second missed call from it replaces the first. */
    private void notifyMissed(Engine e, Peer p) {
        int id = 1000 + (p.id.hashCode() & 0xffff);
        String name = e.config.display(p);
        PendingIntent history =
                PendingIntent.getActivity(
                        this,
                        id,
                        new Intent(this, HistoryActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent callBack =
                PendingIntent.getActivity(
                        this,
                        id + 0x10000,
                        new Intent(this, MainActivity.class)
                                .putExtra("quickPeer", p.id)
                                .addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK
                                                | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        getSystemService(NotificationManager.class)
                .notify(
                        id,
                        new Notification.Builder(this, "missed")
                                .setSmallIcon(R.drawable.ic_notification)
                                .setContentTitle(getString(R.string.msg_call_missed))
                                .setContentText(name)
                                .setCategory(Notification.CATEGORY_MISSED_CALL)
                                .setShowWhen(true)
                                .setContentIntent(history)
                                .setAutoCancel(true)
                                .addAction(
                                        new Notification.Action.Builder(
                                                        null,
                                                        getString(R.string.notif_call_back),
                                                        callBack)
                                                .build())
                                .build());
    }

    private String ongoingText(Engine e) {
        if (e.state.equals("broadcasting")) return getString(R.string.notif_announcement_running);
        if (e.active()) {
            String name =
                    e.peer == null ? getString(R.string.fallback_kiosk) : e.config.display(e.peer);
            return getString(R.string.notif_call_with, name);
        }
        if (e.config.dnd()) return getString(R.string.notif_dnd_active);
        if (e.config.androidDnd()) return getString(R.string.notif_android_dnd_active);
        if (e.config.quiet()) return getString(R.string.notif_quiet_active);
        return getString(R.string.notif_reachable);
    }

    private void stopRing() {
        if (ring != null) {
            ring.stop();
            ring = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        if (screen != null) {
            if (screen.isHeld()) screen.release();
            screen = null;
        }
        ringingId = "";
    }

    /**
     * Turns the display on while a call rings, also where the full-screen call screen isn't
     * allowed and only the notification shows. The display then goes off as it normally would.
     */
    @SuppressWarnings("deprecation") // no other way for a service; activities use setTurnScreenOn
    private void wakeScreen() {
        screen =
                getSystemService(PowerManager.class)
                        .newWakeLock(
                                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                        | PowerManager.ON_AFTER_RELEASE,
                                "IntercomSatellite:ringing");
        screen.acquire(60_000);
    }

    /** Vibrates while a call rings, as the phone does for its own calls; silent mode stays silent. */
    private void vibrate() {
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null || audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return;
        Vibrator v = getSystemService(VibratorManager.class).getDefaultVibrator();
        if (!v.hasVibrator()) return;
        vibrator = v;
        v.vibrate(
                VibrationEffect.createWaveform(new long[] {0, 800, 800}, 0),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build());
    }

    /**
     * Turns the screen off while the phone is held to the ear, so a cheek cannot tap the call
     * screen. Calls in the phone app get this from the phone app.
     */
    private void syncProximity(Engine e) {
        boolean near = e.active() && !e.phoneManaged && e.audio.earpiece();
        if (near && proximity == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return;
            proximity =
                    pm.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                            "IntercomSatellite:proximity");
            // Released when the call leaves the earpiece; the timeout only guards against a leak.
            proximity.acquire(4 * 60 * 60 * 1000L);
        } else if (!near && proximity != null) {
            releaseProximity();
        }
    }

    private void releaseProximity() {
        if (proximity == null) return;
        // A phone still at the ear keeps its screen off until it is taken away.
        if (proximity.isHeld()) proximity.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
        proximity = null;
    }

    /**
     * The button of a Bluetooth or wired headset answers a ringing call and ends a running one.
     * Calls in the phone app get this from Android.
     */
    private void syncButtons(Engine e) {
        boolean wanted = e.active() && !e.phoneManaged && !e.state.equals("calling");
        if (wanted && buttons == null) {
            buttons = new MediaSession(this, "IntercomSatellite");
            buttons.setCallback(
                    new MediaSession.Callback() {
                        @Override
                        public boolean onMediaButtonEvent(Intent intent) {
                            KeyEvent key = keyEvent(intent);
                            if (key == null || key.getAction() != KeyEvent.ACTION_DOWN) return false;
                            switch (key.getKeyCode()) {
                                case KeyEvent.KEYCODE_HEADSETHOOK:
                                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                                case KeyEvent.KEYCODE_MEDIA_PLAY:
                                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                                    headsetButton();
                                    return true;
                                default:
                                    return false;
                            }
                        }
                    },
                    main);
            buttons.setPlaybackState(
                    new PlaybackState.Builder()
                            .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                            .setState(PlaybackState.STATE_PLAYING, 0, 1)
                            .build());
            buttons.setActive(true);
        } else if (!wanted && buttons != null) {
            releaseButtons();
        }
    }

    @SuppressWarnings("deprecation")
    private static KeyEvent keyEvent(Intent intent) {
        return Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class)
                : intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    }

    private void releaseButtons() {
        if (buttons == null) return;
        buttons.setActive(false);
        buttons.release();
        buttons = null;
    }

    private void headsetButton() {
        Engine e = engine;
        if (e == null || e.phoneManaged) return;
        if (e.state.equals("ringing")) {
            // Answering needs the microphone; without the permission only the app can ask for it.
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) return;
            microphone();
            e.answer();
        } else if (e.active()) {
            e.finish(getString(R.string.msg_call_ended), true);
        }
    }

    public void onDestroy() {
        main.removeCallbacks(availabilityTick);
        instance = null;
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        phone.close();
        // Without the intercom every kiosk shows offline.
        KioskWidget.refreshStatus(this, widgetStatus);
        stopRing();
        releaseProximity();
        releaseButtons();
        getSystemService(NotificationManager.class).cancel(2);
        if (wake != null && wake.isHeld()) wake.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    public IBinder onBind(Intent intent) {
        return null;
    }
}
