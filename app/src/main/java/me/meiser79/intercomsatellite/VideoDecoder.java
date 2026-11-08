package me.meiser79.intercomsatellite;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/**
 * The other kiosk's picture: one H.264 decoder onto the surface the call screen hands over. Without
 * a surface frames are dropped and the next one asks for a key frame.
 */
final class VideoDecoder {
    interface Events {
        /** The decoder needs a key frame from the other side. */
        void requestKey();

        /** A picture of this size reached the screen. */
        void shown(int width, int height);

        void failed(String message);
    }

    private static final int MAX_PENDING = 12;

    /** Hardware first; the software decoder is the last resort and remembered once it worked. */
    private static volatile boolean preferSoftware;

    private final Events events;
    private final HandlerThread thread = new HandlerThread("intercom-video-decode");
    private final Handler handler;
    private final Object lock = new Object();
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private Surface surface;
    private MediaCodec codec;
    private int generation, width, height;
    private byte[] sps, pps;
    private boolean waitingKey = true, firstShown, closed;
    private long lastKeyAsk;

    VideoDecoder(Events events) {
        this.events = events;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** The screen's surface, or null when the call screen is not visible. */
    void surface(Surface next) {
        synchronized (lock) {
            if (next == surface) return;
            stopCodec();
            surface = next;
            waitingKey = true;
        }
        if (next != null) askKey(true);
    }

    /** One frame from the socket, header included. */
    void push(byte[] frame) {
        if (frame.length <= VideoWire.HEADER) return;
        boolean key = VideoWire.isKey(frame);
        int frameWidth = VideoWire.width(frame), frameHeight = VideoWire.height(frame);
        long timeUs = VideoWire.timeMs(frame) * 1000;
        byte[] bytes = VideoWire.payload(frame);
        boolean ask = false;
        synchronized (lock) {
            if (closed || surface == null) return;
            if (key) {
                List<byte[]> units = VideoWire.nals(bytes);
                byte[] nextSps = VideoWire.find(units, VideoWire.NAL_SPS);
                byte[] nextPps = VideoWire.find(units, VideoWire.NAL_PPS);
                if (nextSps == null || nextPps == null) return;
                boolean changed =
                        codec == null
                                || frameWidth != width
                                || frameHeight != height
                                || !Arrays.equals(nextSps, sps)
                                || !Arrays.equals(nextPps, pps);
                if (changed && !start(frameWidth, frameHeight, nextSps, nextPps)) return;
                waitingKey = false;
            }
            if (waitingKey || codec == null) ask = true;
            else {
                Integer index = free.pollFirst();
                if (index != null) queue(index, bytes, timeUs);
                else {
                    pending.addLast(bytes);
                    if (pending.size() > MAX_PENDING) {
                        pending.clear();
                        waitingKey = true;
                        ask = true;
                    }
                }
            }
        }
        if (ask) askKey(false);
    }

    private boolean start(int w, int h, byte[] nextSps, byte[] nextPps) {
        if (preferSoftware) return tryStart(w, h, nextSps, nextPps, true);
        if (tryStart(w, h, nextSps, nextPps, false)) return true;
        if (!tryStart(w, h, nextSps, nextPps, true)) {
            events.failed("No decoder for " + w + "x" + h);
            return false;
        }
        preferSoftware = true;
        return true;
    }

    private static String softwareDecoder() {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            boolean avc = false;
            for (String type : info.getSupportedTypes())
                if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) avc = true;
            if (avc && (info.getName().startsWith("c2.android.") || info.getName().startsWith("OMX.google.")))
                return info.getName();
        }
        return null;
    }

    private boolean tryStart(int w, int h, byte[] nextSps, byte[] nextPps, boolean software) {
        stopCodec();
        int mine = ++generation;
        MediaCodec decoder = null;
        try {
            String name = software ? softwareDecoder() : null;
            if (software && name == null) return false;
            decoder =
                    name != null
                            ? MediaCodec.createByCodecName(name)
                            : MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            // Asking a decoder without the feature fails configure outright on some chips.
            boolean lowLatency =
                    decoder.getCodecInfo()
                            .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(annexB(nextSps)));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(annexB(nextPps)));
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            decoder.setCallback(
                    new MediaCodec.Callback() {
                        @Override
                        public void onInputBufferAvailable(MediaCodec c, int index) {
                            synchronized (lock) {
                                if (mine != generation) return;
                                byte[] next = pending.pollFirst();
                                if (next != null) queue(index, next, 0);
                                else free.addLast(index);
                            }
                        }

                        @Override
                        public void onOutputBufferAvailable(
                                MediaCodec c, int index, MediaCodec.BufferInfo info) {
                            boolean report;
                            synchronized (lock) {
                                if (mine != generation) return;
                                try {
                                    c.releaseOutputBuffer(index, true);
                                } catch (IllegalStateException e) {
                                    return;
                                }
                                report = !firstShown;
                                firstShown = true;
                            }
                            if (report) events.shown(width, height);
                        }

                        @Override
                        public void onOutputFormatChanged(MediaCodec c, MediaFormat f) {}

                        @Override
                        public void onError(MediaCodec c, MediaCodec.CodecException e) {
                            synchronized (lock) {
                                if (mine != generation) return;
                                stopCodec();
                                waitingKey = true;
                            }
                            askKey(false);
                        }
                    },
                    handler);
            decoder.configure(format, surface, null, 0);
            decoder.start();
            codec = decoder;
            sps = nextSps;
            pps = nextPps;
            width = w;
            height = h;
            firstShown = false;
            return true;
        } catch (Exception e) {
            if (decoder != null)
                try {
                    decoder.release();
                } catch (RuntimeException ignored) {
                }
            stopCodec();
            return false;
        }
    }

    private static byte[] annexB(byte[] unit) {
        byte[] out = new byte[unit.length + 4];
        out[3] = 1;
        System.arraycopy(unit, 0, out, 4, unit.length);
        return out;
    }

    private void queue(int index, byte[] bytes, long timeUs) {
        MediaCodec decoder = codec;
        if (decoder == null) return;
        try {
            ByteBuffer input = decoder.getInputBuffer(index);
            if (input == null) return;
            input.clear();
            if (bytes.length > input.capacity()) {
                free.addLast(index);
                waitingKey = true;
                return;
            }
            input.put(bytes);
            decoder.queueInputBuffer(index, 0, bytes.length, timeUs, 0);
        } catch (IllegalStateException ignored) {
        }
    }

    private void stopCodec() {
        generation++;
        free.clear();
        pending.clear();
        MediaCodec decoder = codec;
        codec = null;
        if (decoder == null) return;
        try {
            decoder.stop();
        } catch (Exception ignored) {
        }
        try {
            decoder.release();
        } catch (Exception ignored) {
        }
    }

    /** Asks the other side for a key frame, at most once a second unless forced. */
    private void askKey(boolean force) {
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            if (!force && now - lastKeyAsk < 1000) return;
            lastKeyAsk = now;
        }
        events.requestKey();
    }

    /** Releases the codec off the caller's thread: a codec release is a binder call. */
    void close() {
        handler.post(
                () -> {
                    synchronized (lock) {
                        closed = true;
                        stopCodec();
                    }
                    thread.quitSafely();
                });
    }
}
