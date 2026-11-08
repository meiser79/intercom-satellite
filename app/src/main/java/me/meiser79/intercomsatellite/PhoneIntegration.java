package me.meiser79.intercomsatellite;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.*;

import java.util.Collections;

/** System-managed account: the user's existing dialer owns the call UI and audio route. */
final class PhoneIntegration {
    static final String CALL_ID = "me.meiser79.intercomsatellite.CALL_ID";
    private final IntercomService service;
    private String attempted = "";
    private SatelliteConnection connection;

    PhoneIntegration(IntercomService service) {
        this.service = service;
        register(service);
    }

    static PhoneAccountHandle handle(Context c) {
        return new PhoneAccountHandle(
                new ComponentName(c, IntercomConnectionService.class), "intercom");
    }

    static boolean register(Context c) {
        try {
            TelecomManager manager = c.getSystemService(TelecomManager.class);
            if (manager == null) return false;
            manager.registerPhoneAccount(
                    PhoneAccount.builder(handle(c), "Intercom Satellite")
                            .setShortDescription(c.getString(R.string.phone_account_description))
                            .setCapabilities(
                                    PhoneAccount.CAPABILITY_CALL_PROVIDER
                                            | PhoneAccount.CAPABILITY_VIDEO_CALLING
                                            | PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING)
                            .setSupportedUriSchemes(
                                    Collections.singletonList(PhoneAccount.SCHEME_SIP))
                            .build());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean enabled(Context c) {
        try {
            TelecomManager manager = c.getSystemService(TelecomManager.class);
            if (manager == null) return false;
            if (Build.VERSION.SDK_INT >= 35) {
                for (PhoneAccount a : manager.getRegisteredPhoneAccounts())
                    if (handle(c).equals(a.getAccountHandle())) return a.isEnabled();
                return false;
            }
            if (c.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS)
                    != PackageManager.PERMISSION_GRANTED) return false;
            PhoneAccount a = manager.getPhoneAccount(handle(c));
            return a != null && a.isEnabled();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean ready(Context c) {
        return enabled(c)
                && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /** What the phone app may offer: send and receive, receive only, or voice. */
    private static int offeredVideo(Engine e) {
        if (!e.peerVideo) return VideoProfile.STATE_AUDIO_ONLY;
        return e.video.cameraAllowed()
                ? VideoProfile.STATE_BIDIRECTIONAL
                : VideoProfile.STATE_RX_ENABLED;
    }

    void sync(Engine e) {
        if (connection != null && connection.session.current()) connection.syncVideo(e);
        if (connection != null && !connection.session.current()) {
            // A call that stopped ringing without being taken here counts as missed, also when
            // the kiosk gave up first; only MISSED gets the phone app's missed-call notice.
            boolean unanswered =
                    connection.getState() == Connection.STATE_RINGING
                            && (!e.endedLocally
                                    || e.detail.equals(e.msg.get(R.string.msg_call_missed)));
            connection.close(
                    unanswered
                            ? DisconnectCause.MISSED
                            : e.detail.equals(e.msg.get(R.string.msg_call_declined))
                                    ? DisconnectCause.REJECTED
                                    : e.endedLocally
                                            ? DisconnectCause.LOCAL
                                            : DisconnectCause.REMOTE);
            connection = null;
        }
        synchronized (e) {
            if (e.state.equals("ringing")
                    && !e.outgoing
                    && !e.broadcast
                    && !attempted.equals(e.callId)) {
                attempted = e.callId;
                if (!ready(service)) return;
                e.phoneManaged = true;
                e.audio.telecomManaged(true);
                try {
                    Bundle extras = new Bundle();
                    extras.putString(CALL_ID, e.callId);
                    extras.putParcelable(
                            TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, callerAddress(e));
                    extras.putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, offeredVideo(e));
                    service.getSystemService(TelecomManager.class)
                            .addNewIncomingCall(handle(service), extras);
                    e.diagnostics.event("Call handed to Android phone app");
                } catch (RuntimeException ex) {
                    e.phoneManaged = false;
                    e.audio.telecomManaged(false);
                    e.diagnostics.error("Android call display failed; falling back to app display", ex);
                }
            }
        }
    }

    /**
     * Address shown or logged by the phone app. Some call logs strip everything that is not a
     * dialable character from a plain name, but keep a SIP address with a host part intact,
     * so the kiosk's name (alias or device name) is combined with a fixed host.
     * Calls are matched by CALL_ID, never by this address.
     */
    private static Uri callerAddress(Engine e) {
        return Uri.fromParts("sip", e.config.display(e.peer) + "@Intercom", null);
    }

    SatelliteConnection create(String id, IntercomConnectionService owner) {
        Engine e = service.engine;
        if (e == null) return null;
        synchronized (e) {
            if (!e.phoneManaged
                    || !id.equals(e.callId)
                    || !e.state.equals("ringing")
                    || connection != null) return null;
            connection = new SatelliteConnection(e, id, service::phoneMicrophone, owner.hasFocus);
            connection.setAddress(callerAddress(e), TelecomManager.PRESENTATION_ALLOWED);
            connection.setCallerDisplayName(
                    e.config.display(e.peer), TelecomManager.PRESENTATION_ALLOWED);
            connection.setAudioModeIsVoip(true);
            int capabilities = Connection.CAPABILITY_MUTE;
            if (e.peerVideo) {
                capabilities |= Connection.CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL;
                capabilities |=
                        e.video.cameraAllowed()
                                ? Connection.CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL
                                : Connection.CAPABILITY_SUPPORTS_VT_LOCAL_RX;
                connection.video(new PhoneVideo(e), offeredVideo(e));
            }
            connection.setConnectionCapabilities(capabilities);
            connection.setInitialized();
            connection.setRinging();
            e.diagnostics.event("Phone app shows incoming call");
            return connection;
        }
    }

    void failed(String id) {
        Engine e = service.engine;
        if (e != null) e.endIf(id, e.msg.get(R.string.err_phone_display), true);
    }

    void focus(boolean gained) {
        if (connection != null) {
            if (gained) connection.session.focusGained();
            else connection.session.focusLost();
        }
    }

    void close() {
        if (connection != null) {
            connection.close(DisconnectCause.LOCAL);
            connection = null;
        }
    }
}
