package me.meiser79.intercomsatellite;

import android.net.Uri;
import android.telecom.Connection;
import android.telecom.VideoProfile;
import android.view.Surface;

/**
 * The call's video as Android's phone app sees it. The phone app hands over its own surfaces for
 * the kiosk's picture and the self view, turns the camera on and off with session modify requests
 * and picks a camera; the picture comes from the same {@link VideoPipe} as in the app.
 */
final class PhoneVideo extends Connection.VideoProvider {
    private final Engine engine;
    private int peerWidth, peerHeight, selfWidth, selfHeight;

    PhoneVideo(Engine engine) {
        this.engine = engine;
    }

    /** The call's video state as Telecom names it. */
    static int state(Engine e) {
        return (e.cameraOn ? VideoProfile.STATE_TX_ENABLED : 0)
                | (e.farCamera ? VideoProfile.STATE_RX_ENABLED : 0);
    }

    @Override
    public void onSetCamera(String cameraId) {
        // Null means the phone app let go of the camera, as when it pauses video.
        if (cameraId == null) engine.camera(false);
        else engine.useCamera(cameraId);
    }

    @Override
    public void onSetPreviewSurface(Surface surface) {
        engine.video.selfSurface(VideoPipe.PHONE, surface);
    }

    @Override
    public void onSetDisplaySurface(Surface surface) {
        engine.video.farSurface(VideoPipe.PHONE, surface);
    }

    @Override
    public void onSetDeviceOrientation(int rotation) {
        engine.video.rotation(rotation);
    }

    @Override
    public void onSetZoom(float value) {}

    @Override
    public void onSendSessionModifyRequest(VideoProfile from, VideoProfile to) {
        boolean send = VideoProfile.isTransmissionEnabled(to.getVideoState());
        // Turning the camera on here is a tap in the phone app, like Turn camera on in the app.
        boolean ok = (!send || engine.video.cameraAllowed()) && engine.camera(send);
        receiveSessionModifyResponse(
                ok
                        ? Connection.VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS
                        : Connection.VideoProvider.SESSION_MODIFY_REQUEST_FAIL,
                to,
                new VideoProfile(state(engine)));
    }

    @Override
    public void onSendSessionModifyResponse(VideoProfile response) {}

    @Override
    public void onRequestCameraCapabilities() {
        selfWidth = selfHeight = 0;
        update();
    }

    @Override
    public void onRequestConnectionDataUsage() {
        setCallDataUsage(0);
    }

    @Override
    public void onSetPauseImage(Uri uri) {}

    /** Tells the phone app the pictures' sizes when they change. */
    void update() {
        VideoPipe v = engine.video;
        int farWidth = v.farWidth, farHeight = v.farHeight;
        if (farWidth > 0 && (farWidth != peerWidth || farHeight != peerHeight)) {
            peerWidth = farWidth;
            peerHeight = farHeight;
            changePeerDimensions(farWidth, farHeight);
        }
        int width = v.selfWidth, height = v.selfHeight;
        if (width > 0 && (width != selfWidth || height != selfHeight)) {
            selfWidth = width;
            selfHeight = height;
            changeCameraCapabilities(new VideoProfile.CameraCapabilities(width, height));
        }
    }

    /** The phone app's surfaces are gone with the call. */
    void release() {
        engine.video.selfSurface(VideoPipe.PHONE, null);
        engine.video.farSurface(VideoPipe.PHONE, null);
    }
}
