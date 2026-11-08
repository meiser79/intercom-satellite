package me.meiser79.intercomsatellite;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.view.Surface;

import java.util.Objects;

/**
 * The video hardware of a call: this device's camera and the decoder for the other side's picture.
 * Two screens can show a call, the app's call screen and the Android phone app; each hands over
 * its surfaces as they come and go, and the phone app's win while it has them. The call itself
 * lives in {@link Engine}.
 */
class VideoPipe {
    interface Sink {
        /** An encoded frame of this device's camera. */
        void frame(byte[] frame);

        /** The decoder needs a key frame from the other side. */
        void requestKey();

        /** The camera could not send. */
        void failed(String message);

        void changed();
    }

    /** Who hands over a surface: the call screen, the phone app, or the picture-in-picture window. */
    static final int APP = 0, PHONE = 1, PIP = 2;

    private final Context context;
    private final Sink sink;
    private CameraSender camera;
    private VideoDecoder far;
    private final Surface[] farSurfaces = new Surface[3];
    /** A SurfaceTexture (app) or a Surface (phone app). */
    private final Object[] selfWindows = new Object[3];
    private Object selfWindow;
    private Surface farSurface;
    private int rotation;
    private String cameraId;
    volatile int selfWidth, selfHeight, farWidth, farHeight;
    volatile boolean farShown;
    private Boolean hasCamera, canSwitch;

    VideoPipe(Context c, Sink sink) {
        context = c;
        this.sink = sink;
    }

    synchronized boolean hasCamera() {
        if (hasCamera == null)
            hasCamera =
                    context != null
                            && context.getPackageManager()
                                    .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        return hasCamera;
    }

    /** Whether the camera may be used now, permission included. */
    boolean cameraAllowed() {
        return hasCamera()
                && context.checkSelfPermission(android.Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED;
    }

    synchronized boolean cameraOn() {
        return camera != null;
    }

    synchronized void startCamera(String quality) {
        if (camera != null) return;
        camera =
                new CameraSender(
                        context,
                        quality,
                        cameraId,
                        rotation,
                        new CameraSender.Events() {
                            public void frame(byte[] frame) {
                                sink.frame(frame);
                            }

                            public void self(int width, int height) {
                                selfWidth = width;
                                selfHeight = height;
                                sink.changed();
                            }

                            public void failed(String message) {
                                sink.failed(message);
                            }
                        });
        if (selfWindow != null) camera.preview(selfWindow);
    }

    void stopCamera() {
        CameraSender old;
        synchronized (this) {
            old = camera;
            camera = null;
            selfWidth = 0;
            selfHeight = 0;
        }
        if (old != null) old.stop();
    }

    /**
     * The camera the phone app picked, or null for the front one. A running camera restarts on
     * the new one.
     */
    void useCamera(String id, String quality) {
        boolean restart;
        synchronized (this) {
            if (Objects.equals(id, cameraId)) return;
            cameraId = id;
            restart = camera != null;
        }
        if (!restart) return;
        stopCamera();
        startCamera(quality);
    }

    /** Whether there are a front and a back camera to switch between. */
    synchronized boolean canSwitchCamera() {
        if (canSwitch == null)
            canSwitch =
                    facingCamera(CameraCharacteristics.LENS_FACING_FRONT) != null
                            && facingCamera(CameraCharacteristics.LENS_FACING_BACK) != null;
        return canSwitch;
    }

    /** Moves the camera from the front to the back or back again; null picks the front one. */
    void switchCamera(String quality) {
        String next;
        synchronized (this) {
            String back = facingCamera(CameraCharacteristics.LENS_FACING_BACK);
            next = back != null && !back.equals(cameraId) ? back : null;
        }
        useCamera(next, quality);
    }

    /** The first camera facing {@code facing}, or null. */
    private String facingCamera(int facing) {
        if (context == null) return null;
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            for (String id : manager.getCameraIdList()) {
                Integer f = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == facing) return id;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    synchronized void keyFrame() {
        if (camera != null) camera.keyFrame();
    }

    /** Starts showing the other side's picture. */
    synchronized void openFar() {
        if (far != null) return;
        farShown = false;
        far =
                new VideoDecoder(
                        new VideoDecoder.Events() {
                            public void requestKey() {
                                sink.requestKey();
                            }

                            public void shown(int width, int height) {
                                farWidth = width;
                                farHeight = height;
                                farShown = true;
                                sink.changed();
                            }

                            public void failed(String message) {}
                        });
        far.surface(farSurface);
    }

    synchronized void closeFar() {
        if (far != null) far.close();
        far = null;
        farShown = false;
        farWidth = 0;
        farHeight = 0;
    }

    void receive(byte[] frame) {
        VideoDecoder decoder;
        synchronized (this) {
            decoder = far;
        }
        if (decoder != null) decoder.push(frame);
    }

    /** A screen's surface for the other side's picture, or null when it is gone. */
    synchronized void farSurface(int source, Surface surface) {
        farSurfaces[source] = surface;
        Surface next =
                farSurfaces[PHONE] != null
                        ? farSurfaces[PHONE]
                        : farSurfaces[PIP] != null ? farSurfaces[PIP] : farSurfaces[APP];
        if (next == farSurface) return;
        farSurface = next;
        if (far != null) far.surface(next);
    }

    /**
     * A screen's self view, a SurfaceTexture or a Surface, or null when it is gone. Returns once
     * the camera no longer draws into a view taken away.
     */
    void selfSurface(int source, Object window) {
        CameraSender current;
        Object next;
        synchronized (this) {
            selfWindows[source] = window;
            next =
                    selfWindows[PHONE] != null
                            ? selfWindows[PHONE]
                            : selfWindows[PIP] != null ? selfWindows[PIP] : selfWindows[APP];
            if (next == selfWindow) return;
            selfWindow = next;
            current = camera;
        }
        if (current != null) current.preview(next);
    }

    /** The display rotation in degrees. */
    synchronized void rotation(int degrees) {
        if (degrees == rotation) return;
        rotation = degrees;
        if (camera != null) camera.rotation(degrees);
    }

    void stop() {
        stopCamera();
        closeFar();
    }
}
