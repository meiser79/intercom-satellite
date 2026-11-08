package me.meiser79.intercomsatellite;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * This device's camera for a call. The camera draws into a GL texture; each frame is drawn upright
 * (and unmirrored) into an H.264 encoder and, mirrored like a mirror, into the self view. So the
 * picture follows the phone's rotation without a second camera stream.
 */
final class CameraSender {
    interface Events {
        /** One encoded frame, ready for the socket. */
        void frame(byte[] frame);

        /** The picture this side sends, at this size. */
        void self(int width, int height);

        void failed(String message);
    }

    /** Width, height (landscape), frame rate and bitrate of each video quality. */
    static int[] quality(String q) {
        switch (q) {
            case "low":
                return new int[] {640, 480, 15, 500_000};
            case "high":
                return new int[] {1280, 720, 30, 2_500_000};
            default:
                return new int[] {640, 480, 24, 1_000_000};
        }
    }

    private static final String VERTEX =
            "uniform mat4 uMvp;\nuniform mat4 uTex;\nattribute vec4 aPos;\nattribute vec4 aTex;\n"
                    + "varying vec2 vTex;\nvoid main() {\n  gl_Position = uMvp * aPos;\n"
                    + "  vTex = (uTex * aTex).xy;\n}\n";
    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\n"
                    + "varying vec2 vTex;\nuniform samplerExternalOES sTex;\n"
                    + "void main() {\n  gl_FragColor = texture2D(sTex, vTex);\n}\n";
    private static final float[] QUAD = {-1, -1, 1, -1, -1, 1, 1, 1};
    private static final float[] QUAD_TEX = {0, 0, 1, 0, 0, 1, 1, 1};
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private final Context context;
    private final Events events;
    private final int[] quality;
    private final HandlerThread thread = new HandlerThread("intercom-video-camera");
    private final HandlerThread encoderThread = new HandlerThread("intercom-video-encode");
    private final Handler gl, encode;

    // GL thread only.
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE,
            encoderSurface = EGL14.EGL_NO_SURFACE,
            previewSurface = EGL14.EGL_NO_SURFACE;
    private int program, texture, uMvp, uTex, aPos, aTex;
    private final FloatBuffer quad = floats(QUAD), quadTex = floats(QUAD_TEX);
    private final float[] texMatrix = new float[16], mvp = new float[16];
    private SurfaceTexture cameraTexture;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private boolean front;
    private int sensorOrientation, cameraWidth, cameraHeight, rotation;
    private final int[] previewSize = new int[2];
    private volatile MediaCodec encoder;
    private Surface encoderInput;
    private volatile int width, height;
    private long lastEncodedNs;
    private Object preview;
    private final String cameraId;
    private boolean stopped;

    // Encoder thread only.
    private byte[] sps, pps;

    CameraSender(Context context, String quality, String cameraId, int rotation, Events events) {
        this.context = context;
        this.cameraId = cameraId;
        this.events = events;
        this.quality = quality(quality);
        this.rotation = rotation;
        thread.start();
        encoderThread.start();
        gl = new Handler(thread.getLooper());
        encode = new Handler(encoderThread.getLooper());
        gl.post(this::open);
    }

    /** The display rotation in degrees; the picture restarts at the new shape when it turns. */
    void rotation(int degrees) {
        gl.post(
                () -> {
                    if (stopped || degrees == rotation) return;
                    boolean reshape = (degrees - rotation) % 180 != 0;
                    rotation = degrees;
                    if (reshape && encoder != null) {
                        stopEncoder();
                        startEncoder();
                    }
                });
    }

    /**
     * The self view, a SurfaceTexture or a Surface, or null when it is not on screen. Returns once
     * the camera no longer draws into the previous one, so its owner can release it right after.
     */
    void preview(Object next) {
        CountDownLatch done = new CountDownLatch(1);
        gl.post(
                () -> {
                    try {
                        if (stopped || next == preview) return;
                        releasePreview();
                        preview = next;
                        attachPreview();
                    } finally {
                        done.countDown();
                    }
                });
        try {
            done.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void attachPreview() {
        if (preview == null || display == EGL14.EGL_NO_DISPLAY) return;
        try {
            previewSurface =
                    EGL14.eglCreateWindowSurface(
                            display, eglConfig, preview, new int[] {EGL14.EGL_NONE}, 0);
        } catch (RuntimeException e) {
            previewSurface = EGL14.EGL_NO_SURFACE;
        }
    }

    void keyFrame() {
        encode.post(
                () -> {
                    MediaCodec c = encoder;
                    if (c == null) return;
                    Bundle b = new Bundle();
                    b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    try {
                        c.setParameters(b);
                    } catch (IllegalStateException ignored) {
                    }
                });
    }

    /** Stops the camera and waits briefly, so the next call can open it again. */
    void stop() {
        CountDownLatch done = new CountDownLatch(1);
        gl.post(
                () -> {
                    stopped = true;
                    if (session != null)
                        try {
                            session.close();
                        } catch (RuntimeException ignored) {
                        }
                    session = null;
                    if (camera != null) camera.close();
                    camera = null;
                    stopEncoder();
                    releasePreview();
                    if (cameraTexture != null) cameraTexture.release();
                    cameraTexture = null;
                    releaseEgl();
                    done.countDown();
                    thread.quitSafely();
                    encoderThread.quitSafely();
                });
        try {
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- camera

    @SuppressLint("MissingPermission")
    private void open() {
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            String id = pickCamera(manager, cameraId);
            if (id == null) throw new IllegalStateException("no camera");
            CameraCharacteristics info = manager.getCameraCharacteristics(id);
            front = info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
            Integer orientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 0 : orientation;
            StreamConfigurationMap map =
                    info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = pickSize(map.getOutputSizes(SurfaceTexture.class), quality[0], quality[1]);
            cameraWidth = size.getWidth();
            cameraHeight = size.getHeight();
            Range<Integer> fps = pickFps(info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES), quality[2]);

            setUpEgl();
            cameraTexture = new SurfaceTexture(texture);
            cameraTexture.setDefaultBufferSize(cameraWidth, cameraHeight);
            cameraTexture.setOnFrameAvailableListener(t -> draw(), gl);
            startEncoder();
            attachPreview();

            manager.openCamera(
                    id,
                    new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(CameraDevice device) {
                            if (stopped) {
                                device.close();
                                return;
                            }
                            camera = device;
                            startSession(fps);
                        }

                        @Override
                        public void onDisconnected(CameraDevice device) {
                            device.close();
                            if (camera == device) camera = null;
                            if (!stopped) events.failed("Camera disconnected");
                        }

                        @Override
                        public void onError(CameraDevice device, int error) {
                            device.close();
                            if (camera == device) camera = null;
                            if (!stopped) events.failed("Camera error " + error);
                        }
                    },
                    gl);
        } catch (Exception e) {
            events.failed("Camera: " + e.getMessage());
        }
    }

    private static String pickCamera(CameraManager manager, String wanted)
            throws CameraAccessException {
        String fallback = null;
        if (wanted != null && Arrays.asList(manager.getCameraIdList()).contains(wanted))
            return wanted;
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
            if (fallback == null) fallback = id;
        }
        return fallback;
    }

    /** The smallest size at least as large as wanted with its aspect, else the closest one. */
    static Size pickSize(Size[] sizes, int w, int h) {
        Size best = null;
        for (Size s : sizes) {
            boolean aspect = (long) s.getWidth() * h == (long) s.getHeight() * w;
            boolean large = s.getWidth() >= w && s.getHeight() >= h;
            if (aspect && large && (best == null || s.getWidth() < best.getWidth())) best = s;
        }
        if (best != null) return best;
        for (Size s : sizes) {
            long d = Math.abs((long) s.getWidth() * s.getHeight() - (long) w * h);
            if (best == null
                    || d < Math.abs((long) best.getWidth() * best.getHeight() - (long) w * h))
                best = s;
        }
        return best;
    }

    private static Range<Integer> pickFps(Range<Integer>[] ranges, int fps) {
        Range<Integer> best = null;
        if (ranges == null) return null;
        for (Range<Integer> r : ranges) {
            if (!r.contains(fps)) continue;
            if (best == null
                    || r.getUpper() < best.getUpper()
                    || (r.getUpper().equals(best.getUpper()) && r.getLower() > best.getLower()))
                best = r;
        }
        return best;
    }

    private void startSession(Range<Integer> fps) {
        try {
            Surface target = new Surface(cameraTexture);
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(target);
            if (fps != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            camera.createCaptureSession(
                    new SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            Collections.singletonList(new OutputConfiguration(target)),
                            command -> gl.post(command),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession s) {
                                    if (stopped) {
                                        s.close();
                                        return;
                                    }
                                    session = s;
                                    try {
                                        s.setRepeatingRequest(request.build(), null, gl);
                                    } catch (Exception e) {
                                        events.failed("Camera: " + e.getMessage());
                                    }
                                }

                                @Override
                                public void onConfigureFailed(CameraCaptureSession s) {
                                    if (!stopped) events.failed("Camera session failed");
                                }
                            }));
        } catch (Exception e) {
            events.failed("Camera: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- encoder

    private void startEncoder() {
        // The camera texture is upright in the device's natural orientation; a turned display
        // swaps the shape once more.
        boolean naturalPortrait = (sensorOrientation % 180 != 0) == (cameraWidth > cameraHeight);
        boolean portrait = naturalPortrait == (rotation % 180 == 0);
        width = portrait ? quality[1] : quality[0];
        height = portrait ? quality[0] : quality[1];
        encode.post(() -> sps = pps = null);
        try {
            encoder = createEncoder(true);
        } catch (Exception first) {
            try {
                encoder = createEncoder(false);
            } catch (Exception e) {
                encoder = null;
                events.failed("Encoder: " + e.getMessage());
                return;
            }
        }
        encoderSurface =
                EGL14.eglCreateWindowSurface(
                        display, eglConfig, encoderInput, new int[] {EGL14.EGL_NONE}, 0);
        encoder.start();
        events.self(width, height);
    }

    private MediaCodec createEncoder(boolean baseline) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, quality[3]);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, quality[2]);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        // Baseline decodes everywhere, the Echo Shows' decoders included.
        if (baseline)
            format.setInteger(
                    MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            codec.setCallback(
                    new MediaCodec.Callback() {
                        @Override
                        public void onInputBufferAvailable(MediaCodec c, int index) {}

                        @Override
                        public void onOutputBufferAvailable(
                                MediaCodec c, int index, MediaCodec.BufferInfo info) {
                            output(c, index, info);
                        }

                        @Override
                        public void onError(MediaCodec c, MediaCodec.CodecException e) {
                            if (c == encoder) events.failed("Encoder: " + e.getMessage());
                        }

                        @Override
                        public void onOutputFormatChanged(MediaCodec c, MediaFormat f) {
                            ByteBuffer csd0 = f.getByteBuffer("csd-0"), csd1 = f.getByteBuffer("csd-1");
                            if (csd0 != null) config(csd0);
                            if (csd1 != null) config(csd1);
                        }
                    },
                    encode);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInput = codec.createInputSurface();
            return codec;
        } catch (Exception e) {
            codec.release();
            throw e;
        }
    }

    private void config(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        for (byte[] unit : VideoWire.nals(bytes)) {
            if (VideoWire.type(unit) == VideoWire.NAL_SPS) sps = unit;
            else if (VideoWire.type(unit) == VideoWire.NAL_PPS) pps = unit;
        }
    }

    /** One encoded access unit, on the encoder thread. */
    private void output(MediaCodec c, int index, MediaCodec.BufferInfo info) {
        try {
            ByteBuffer buffer = c.getOutputBuffer(index);
            if (buffer == null || info.size == 0) return;
            byte[] bytes = new byte[info.size];
            buffer.position(info.offset);
            buffer.get(bytes, 0, info.size);
            List<byte[]> units = VideoWire.nals(bytes);
            byte[] inlineSps = VideoWire.find(units, VideoWire.NAL_SPS);
            byte[] inlinePps = VideoWire.find(units, VideoWire.NAL_PPS);
            if (inlineSps != null) sps = inlineSps;
            if (inlinePps != null) pps = inlinePps;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;
            boolean key =
                    (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            || VideoWire.find(units, VideoWire.NAL_IDR) != null;
            if (key && (sps == null || pps == null)) return;
            events.frame(
                    VideoWire.pack(
                            key, width, height, info.presentationTimeUs / 1000, sps, pps, units));
        } catch (IllegalStateException ignored) {
        } finally {
            try {
                c.releaseOutputBuffer(index, false);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    private void stopEncoder() {
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext);
            EGL14.eglDestroySurface(display, encoderSurface);
        }
        encoderSurface = EGL14.EGL_NO_SURFACE;
        MediaCodec old = encoder;
        encoder = null;
        if (old != null) {
            try {
                old.stop();
            } catch (RuntimeException ignored) {
            }
            old.release();
        }
        if (encoderInput != null) encoderInput.release();
        encoderInput = null;
    }

    // ---------------------------------------------------------------- GL

    private void setUpEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1))
            throw new IllegalStateException("EGL unavailable");
        int[] attributes = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0)
            throw new IllegalStateException("No EGL config");
        eglConfig = configs[0];
        eglContext =
                EGL14.eglCreateContext(
                        display,
                        eglConfig,
                        EGL14.EGL_NO_CONTEXT,
                        new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE},
                        0);
        pbuffer =
                EGL14.eglCreatePbufferSurface(
                        display,
                        eglConfig,
                        new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE},
                        0);
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX));
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
        GLES20.glLinkProgram(program);
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int shader(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        return s;
    }

    private static FloatBuffer floats(float[] values) {
        FloatBuffer b =
                ByteBuffer.allocateDirect(values.length * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();
        b.put(values).position(0);
        return b;
    }

    /** A new camera frame, on the GL thread. */
    private void draw() {
        if (stopped || cameraTexture == null) return;
        try {
            cameraTexture.updateTexImage();
        } catch (RuntimeException e) {
            return;
        }
        cameraTexture.getTransformMatrix(texMatrix);
        long now = cameraTexture.getTimestamp();
        // The texture is upright in the natural orientation; turn it with the display.
        boolean turned = rotation % 180 != 0;
        boolean naturalPortrait = (sensorOrientation % 180 != 0) == (cameraWidth > cameraHeight);
        int contentShort = Math.min(cameraWidth, cameraHeight),
                contentLong = Math.max(cameraWidth, cameraHeight);
        boolean portrait = naturalPortrait != turned;
        int contentWidth = portrait ? contentShort : contentLong;
        int contentHeight = portrait ? contentLong : contentShort;

        long interval = 1_000_000_000L / quality[2];
        if (encoderSurface != EGL14.EGL_NO_SURFACE && now - lastEncodedNs >= interval * 8 / 10) {
            lastEncodedNs = now;
            // Others see the picture as it is, not mirrored like the self view.
            render(encoderSurface, width, height, contentWidth, contentHeight, front, true);
            EGLExt.eglPresentationTimeANDROID(display, encoderSurface, now);
            EGL14.eglSwapBuffers(display, encoderSurface);
        }
        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            // The view decides its own size: the app's corner or the phone app's preview.
            EGL14.eglQuerySurface(display, previewSurface, EGL14.EGL_WIDTH, previewSize, 0);
            EGL14.eglQuerySurface(display, previewSurface, EGL14.EGL_HEIGHT, previewSize, 1);
            if (previewSize[0] > 0 && previewSize[1] > 0) {
                render(
                        previewSurface,
                        previewSize[0],
                        previewSize[1],
                        contentWidth,
                        contentHeight,
                        false,
                        true);
                EGL14.eglSwapBuffers(display, previewSurface);
            }
        }
    }

    private void render(
            EGLSurface target,
            int w,
            int h,
            int contentWidth,
            int contentHeight,
            boolean flip,
            boolean crop) {
        if (!EGL14.eglMakeCurrent(display, target, target, eglContext)) return;
        GLES20.glViewport(0, 0, w, h);
        float content = (float) contentWidth / contentHeight, surface = (float) w / h;
        float sx = 1, sy = 1;
        if (crop == content > surface) sx = content / surface;
        else sy = surface / content;
        Matrix.setIdentityM(mvp, 0);
        Matrix.scaleM(mvp, 0, sx, sy, 1);
        if (flip) Matrix.scaleM(mvp, 0, -1, 1, 1);
        Matrix.rotateM(mvp, 0, rotation, 0, 0, 1);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void releasePreview() {
        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext);
            EGL14.eglDestroySurface(display, previewSurface);
        }
        previewSurface = EGL14.EGL_NO_SURFACE;
    }

    private void releaseEgl() {
        if (display == EGL14.EGL_NO_DISPLAY) return;
        EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer);
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(display);
        display = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        pbuffer = EGL14.EGL_NO_SURFACE;
    }
}
