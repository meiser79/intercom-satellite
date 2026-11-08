package me.meiser79.intercomsatellite;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;

/**
 * The in-call screen (incoming, outgoing, announcement). Views are created once and updated in
 * place.
 */
final class CallPanel {
    interface Host {
        Engine engine();

        void acceptCall();

        /** Answers with the camera on, after asking for it. */
        void acceptVideoCall();

        /** Turns the camera on (after asking for it) or off. */
        void toggleCamera();

        void trust(Peer peer);
    }

    /** A picture that stopped arriving for this long says so instead of freezing silently. */
    private static final long VIDEO_STALL_MS = 3000;

    private final View root;
    private final Config config;
    private final Host host;
    private final TextView tag, name, status, timer, error, hint;
    private final ImageView glyph;
    private final LevelMeter meter;
    private final MaterialButton trust, accept, acceptVideo, camera, switchCamera, talk, decline;
    private final MaterialButton speaker, pushToTalk;
    private final View video, avatar, selfCard, controls;
    private final TextureView farView, selfView;
    private final TextView videoNote;
    private Surface farSurface;
    private int shownFarWidth, shownFarHeight, shownSelfWidth, shownSelfHeight, shownMaxHeight,
            rotation = -1;

    private boolean touchHeld;
    private int finishReason = R.string.msg_call_ended;

    CallPanel(View root, Config config, Host host) {
        this.root = root;
        this.config = config;
        this.host = host;
        tag = root.findViewById(R.id.call_tag);
        glyph = root.findViewById(R.id.call_glyph);
        name = root.findViewById(R.id.call_name);
        status = root.findViewById(R.id.call_status);
        timer = root.findViewById(R.id.call_timer);
        error = root.findViewById(R.id.call_error);
        hint = root.findViewById(R.id.call_hint);
        meter = root.findViewById(R.id.level_meter);
        trust = root.findViewById(R.id.btn_trust);
        accept = root.findViewById(R.id.btn_accept);
        acceptVideo = root.findViewById(R.id.btn_accept_video);
        camera = root.findViewById(R.id.btn_camera);
        switchCamera = root.findViewById(R.id.btn_switch_camera);
        controls = root.findViewById(R.id.call_controls);
        video = root.findViewById(R.id.call_video);
        avatar = root.findViewById(R.id.call_avatar);
        selfCard = root.findViewById(R.id.video_self_card);
        farView = root.findViewById(R.id.video_far);
        selfView = root.findViewById(R.id.video_self);
        videoNote = root.findViewById(R.id.video_note);
        talk = root.findViewById(R.id.btn_talk);
        decline = root.findViewById(R.id.btn_decline);
        speaker = root.findViewById(R.id.btn_speaker);
        pushToTalk = root.findViewById(R.id.btn_push_to_talk);

        meter.setLevelSource(
                () -> {
                    Engine e = host.engine();
                    return e == null ? 0 : e.audio.level;
                });
        accept.setOnClickListener(v -> host.acceptCall());
        acceptVideo.setOnClickListener(v -> host.acceptVideoCall());
        camera.setOnClickListener(v -> host.toggleCamera());
        switchCamera.setOnClickListener(
                v -> {
                    Engine e = host.engine();
                    if (e != null) e.switchCamera();
                });
        setUpVideoViews();
        trust.setOnClickListener(
                v -> {
                    Engine e = host.engine();
                    if (e != null && e.peer != null) host.trust(e.peer);
                });
        decline.setOnClickListener(
                v -> {
                    Engine e = host.engine();
                    if (e != null) e.finish(root.getContext().getString(finishReason), true);
                });
        setUpTalkButton();

        // The buttons toggle themselves on a tap; the next render sets them from the engine.
        pushToTalk.setOnClickListener(
                v -> {
                    Engine e = host.engine();
                    if (e == null) return;
                    e.handsFree(!e.handsFree);
                    updateTalk(e);
                });
        speaker.setOnClickListener(
                v -> {
                    Engine e = host.engine();
                    if (e == null) return;
                    e.speaker = !e.speaker;
                    e.audio.speaker(e.speaker);
                    updateTalk(e);
                });
    }

    /** Hands the picture surfaces to the call's video while they exist. */
    private void setUpVideoViews() {
        farView.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                        farSurface = new Surface(t);
                        Engine e = host.engine();
                        if (e != null) e.video.farSurface(VideoPipe.APP, farSurface);
                    }

                    public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {}

                    public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                        Engine e = host.engine();
                        if (e != null) e.video.farSurface(VideoPipe.APP, null);
                        if (farSurface != null) farSurface.release();
                        farSurface = null;
                        return true;
                    }

                    public void onSurfaceTextureUpdated(SurfaceTexture t) {}
                });
        selfView.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                        Engine e = host.engine();
                        if (e != null) e.video.selfSurface(VideoPipe.APP, t);
                    }

                    public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {}

                    public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                        Engine e = host.engine();
                        if (e != null) e.video.selfSurface(VideoPipe.APP, null);
                        return true;
                    }

                    public void onSurfaceTextureUpdated(SurfaceTexture t) {}
                });
    }

    /** Hold to talk, or tap to toggle the microphone while hands-free is on. */
    private void setUpTalkButton() {
        talk.setOnTouchListener(
                (v, event) -> {
                    Engine e = host.engine();
                    if (e == null) return true;
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        if (!e.handsFree) {
                            touchHeld = true;
                            e.talk(true);
                            updateTalk(e);
                        }
                        return true;
                    }
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        if (touchHeld) {
                            e.talk(false);
                            updateTalk(e);
                        }
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        if (action == MotionEvent.ACTION_UP) v.performClick();
                        else touchHeld = false;
                    }
                    return true;
                });
        talk.setOnClickListener(
                v -> {
                    if (touchHeld) {
                        touchHeld = false;
                        return;
                    }
                    Engine e = host.engine();
                    if (e == null) return;
                    if (e.handsFree) e.talk(!e.sending);
                    else e.handsFree(true);
                    updateTalk(e);
                });
    }

    /** Structural update: which controls are visible and what the texts say. */
    void render(Engine e) {
        boolean ringing = e.state.equals("ringing");
        boolean live = e.state.equals("in_call") || e.state.equals("broadcasting");
        boolean phone = e.phoneManaged;

        tag.setText(
                ringing
                        ? R.string.tag_incoming
                        : e.broadcast ? R.string.tag_announcement : R.string.tag_call);
        glyph.setImageResource(
                e.broadcast
                        ? R.drawable.ic_volume_up
                        : e.peer == null
                                ? R.drawable.ic_call
                                : RoomIcons.drawable(config.icon(e.peer.id)));
        name.setText(
                e.state.equals("broadcasting")
                        ? e.announcementTitle
                        : e.peer == null
                                ? root.getContext().getString(R.string.fallback_kiosk)
                                : config.display(e.peer));
        status.setText(statusText(e));

        boolean showError = !e.detail.isEmpty() && !live;
        error.setVisibility(showError ? View.VISIBLE : View.GONE);
        error.setText(e.detail);
        trust.setVisibility(
                e.peer != null && !e.peer.candidatePin.isEmpty() ? View.VISIBLE : View.GONE);

        accept.setVisibility(!phone && ringing ? View.VISIBLE : View.GONE);
        boolean cameraUsable = e.peerVideo && !e.broadcast && e.cameraReady();
        acceptVideo.setVisibility(!phone && ringing && cameraUsable ? View.VISIBLE : View.GONE);
        boolean callUnderway =
                e.state.equals("calling") || e.state.equals("connecting") || e.state.equals("in_call");
        camera.setVisibility(callUnderway && cameraUsable ? View.VISIBLE : View.GONE);
        switchCamera.setVisibility(
                callUnderway && cameraUsable && e.cameraOn && e.video.canSwitchCamera()
                        ? View.VISIBLE
                        : View.GONE);
        camera.setChecked(e.cameraOn);
        String cameraLabel =
                root.getContext()
                        .getString(e.cameraOn ? R.string.camera_turn_off : R.string.camera_turn_on);
        camera.setContentDescription(cameraLabel);
        camera.setTooltipText(cameraLabel);
        renderVideo(e);
        talk.setVisibility(!phone && live ? View.VISIBLE : View.GONE);
        speaker.setVisibility(!phone && live ? View.VISIBLE : View.GONE);
        pushToTalk.setVisibility(!phone && live ? View.VISIBLE : View.GONE);
        controls.setVisibility(
                camera.getVisibility() == View.VISIBLE || speaker.getVisibility() == View.VISIBLE
                        ? View.VISIBLE
                        : View.GONE);
        hint.setVisibility(phone || live ? View.VISIBLE : View.GONE);

        if (phone) {
            hint.setText(
                    ringing
                            ? R.string.phone_hint_ringing
                            : !e.audio.microphoneAvailable && e.state.equals("in_call")
                                    ? R.string.phone_hint_no_mic
                                    : R.string.phone_hint_active);
        } else if (live) {
            updateTalk(e);
        }

        if (phone) {
            decline.setText(ringing ? R.string.decline : R.string.end_call);
            finishReason = ringing ? R.string.msg_call_declined : R.string.msg_hung_up_phone;
        } else if (ringing) {
            decline.setText(R.string.decline);
            finishReason = R.string.msg_call_declined;
        } else if (live) {
            decline.setText(R.string.end_call);
            finishReason = R.string.msg_call_ended;
        } else {
            decline.setText(e.broadcast ? R.string.close_announcement : R.string.cancel_call);
            finishReason = R.string.msg_call_ended;
        }
    }

    /** Where the kiosk's picture is on screen, or null when it is not shown. */
    Rect farBounds() {
        Rect r = new Rect();
        return farView.isShown() && farView.getGlobalVisibleRect(r) ? r : null;
    }

    /** Where this phone's camera is on screen, or null when it is not shown. */
    Rect selfBounds() {
        Rect r = new Rect();
        return selfView.isShown() && selfView.getGlobalVisibleRect(r) ? r : null;
    }

    /** The picture area: the other side's picture with this side's camera in the corner. */
    private void renderVideo(Engine e) {
        boolean show = e.cameraOn || e.farCamera;
        video.setVisibility(show ? View.VISIBLE : View.GONE);
        avatar.setVisibility(show ? View.GONE : View.VISIBLE);
        farView.setVisibility(e.farCamera ? View.VISIBLE : View.INVISIBLE);
        selfCard.setVisibility(e.cameraOn ? View.VISIBLE : View.GONE);
        shownFarWidth = shownFarHeight = shownSelfWidth = shownSelfHeight = -1;
        if (show) layoutVideo(e);
        updateVideoNote(e);
    }

    /** Sizes the picture area to the pictures' shapes; they change when either phone turns. */
    private void layoutVideo(Engine e) {
        VideoPipe v = e.video;
        int farWidth = v.farWidth, farHeight = v.farHeight;
        int selfWidth = v.selfWidth, selfHeight = v.selfHeight;
        int maxHeight = maxVideoHeight();
        if (farWidth == shownFarWidth
                && farHeight == shownFarHeight
                && selfWidth == shownSelfWidth
                && selfHeight == shownSelfHeight
                && maxHeight == shownMaxHeight) return;
        int available = root.getWidth() > 0 ? root.getWidth() : root.getResources().getDisplayMetrics().widthPixels;
        float density = root.getResources().getDisplayMetrics().density;
        // The card around the picture has 16dp padding.
        int width = Math.max(1, available - Math.round(32 * density));
        int frameHeight =
                farWidth > 0 && farHeight > 0
                        ? Math.min(maxHeight, Math.round((float) width * farHeight / farWidth))
                        : Math.min(maxHeight, width * 3 / 4);
        setSize(video, ViewGroup.LayoutParams.MATCH_PARENT, frameHeight);
        if (farWidth > 0 && farHeight > 0) {
            float scale = Math.min((float) width / farWidth, (float) frameHeight / farHeight);
            setSize(farView, Math.round(farWidth * scale), Math.round(farHeight * scale));
        } else setSize(farView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int selfLong = Math.round(128 * density);
        if (selfWidth > 0 && selfHeight > 0) {
            if (selfHeight >= selfWidth) setSize(selfCard, selfLong * selfWidth / selfHeight, selfLong);
            else setSize(selfCard, selfLong, selfLong * selfHeight / selfWidth);
        }
        shownFarWidth = farWidth;
        shownFarHeight = farHeight;
        shownSelfWidth = selfWidth;
        shownSelfHeight = selfHeight;
        shownMaxHeight = maxHeight;
    }

    /**
     * The picture gets what the screen has left beside the rest of the call screen, so the end
     * button stays in view without scrolling; at least 160dp.
     */
    private int maxVideoHeight() {
        float density = root.getResources().getDisplayMetrics().density;
        View scroll = root.getRootView().findViewById(R.id.scroll);
        if (scroll == null || scroll.getHeight() == 0 || root.getHeight() == 0)
            return Math.round(root.getResources().getDisplayMetrics().heightPixels * 0.3f);
        int others = root.getHeight() - (video.getVisibility() == View.VISIBLE ? video.getHeight() : 0);
        // The 16dp padding around the call screen, above and below.
        int room =
                scroll.getHeight()
                        - scroll.getPaddingTop()
                        - scroll.getPaddingBottom()
                        - Math.round(32 * density)
                        - others;
        return Math.max(Math.round(160 * density), room);
    }

    private static void setSize(View view, int width, int height) {
        ViewGroup.LayoutParams p = view.getLayoutParams();
        if (p.width == width && p.height == height) return;
        p.width = width;
        p.height = height;
        view.setLayoutParams(p);
    }

    private void updateVideoNote(Engine e) {
        int note = 0;
        if (!e.farCamera) note = e.cameraOn ? R.string.video_far_off : 0;
        else if (!e.video.farShown) note = R.string.video_waiting;
        else if (System.currentTimeMillis() - e.lastFarFrame > VIDEO_STALL_MS)
            note = R.string.video_reconnecting;
        videoNote.setVisibility(note == 0 ? View.GONE : View.VISIBLE);
        if (note != 0) videoNote.setText(note);
    }

    /** Cheap per-tick update: elapsed time and level meter. */
    void tick(Engine e) {
        long seconds = e.since == 0 ? 0 : (System.currentTimeMillis() - e.since) / 1000;
        timer.setText(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
        meter.invalidate();
        if (video.getVisibility() == View.VISIBLE) {
            layoutVideo(e);
            updateVideoNote(e);
        }
        int turned = displayRotation();
        if (turned != rotation) {
            rotation = turned;
            e.video.rotation(turned);
        }
    }

    private int displayRotation() {
        Display display = root.getDisplay();
        return display == null ? 0 : display.getRotation() * 90;
    }

    private String statusText(Engine e) {
        int res;
        switch (e.state) {
            case "calling":
                res = R.string.status_calling;
                break;
            case "ringing":
                res = R.string.status_ringing;
                break;
            case "connecting":
                res = R.string.status_connecting;
                break;
            case "listening":
                res = R.string.status_listening;
                break;
            case "broadcasting":
                return e.detail;
            default:
                res = R.string.status_connected;
        }
        return root.getContext().getString(res);
    }

    private void updateTalk(Engine e) {
        boolean mic = e.audio.microphoneAvailable;
        talk.setEnabled(mic);
        pushToTalk.setEnabled(mic);
        if (!mic) {
            talk.setText(R.string.talk_unavailable);
            hint.setText(R.string.mic_unavailable_hint);
        } else if (e.handsFree) {
            talk.setText(e.sending ? R.string.talk_mute : R.string.talk_unmute);
            hint.setText(e.sending ? R.string.hint_handsfree_on : R.string.hint_muted);
        } else {
            talk.setText(e.sending ? R.string.talk_speaking : R.string.talk_hold);
            hint.setText(e.sending ? R.string.hint_transmitting : R.string.hint_hold);
        }
        pushToTalk.setChecked(!e.handsFree);
        speaker.setChecked(e.speaker);
    }
}
