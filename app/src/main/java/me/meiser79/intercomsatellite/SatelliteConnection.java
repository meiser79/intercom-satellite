package me.meiser79.intercomsatellite;

import android.telecom.*;

final class SatelliteConnection extends Connection {
    final PhoneCallSession session;
    private PhoneVideo video;

    SatelliteConnection(Engine e, String id, Runnable prepare, boolean focus) {
        session =
                new PhoneCallSession(
                        e,
                        id,
                        prepare,
                        new PhoneCallSession.Display() {
                            public void accepted() {
                                setActive();
                            }

                            public void closed(int cause) {
                                if (video != null) video.release();
                                setDisconnected(new DisconnectCause(cause));
                                destroy();
                            }
                        },
                        focus);
    }

    /** Lets the phone app show the call's video. */
    void video(PhoneVideo provider, int state) {
        video = provider;
        setVideoProvider(provider);
        setVideoState(state);
    }

    /** Follows the cameras of both sides. */
    void syncVideo(Engine e) {
        if (video == null) return;
        int state = PhoneVideo.state(e);
        if (getVideoState() != state && getState() == STATE_ACTIVE) setVideoState(state);
        video.update();
    }

    @Override
    public void onAnswer() {
        session.answer();
    }

    @Override
    public void onAnswer(int videoState) {
        session.answer(video != null && VideoProfile.isTransmissionEnabled(videoState));
    }

    @Override
    public void onReject() {
        session.end(session.text(R.string.msg_call_declined), DisconnectCause.REJECTED);
    }

    @Override
    public void onReject(int reason) {
        onReject();
    }

    @Override
    public void onReject(String message) {
        onReject();
    }

    @Override
    public void onDisconnect() {
        session.end(session.text(R.string.msg_hung_up_in_phone_app), DisconnectCause.LOCAL);
    }

    @Override
    public void onAbort() {
        session.end(session.text(R.string.msg_phone_call_cancelled), DisconnectCause.CANCELED);
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState audio) {
        if (audio != null) session.mute(audio.isMuted());
    }

    @Override
    public void onMuteStateChanged(boolean muted) {
        session.mute(muted);
    }

    void close(int cause) {
        session.close(cause);
    }
}
