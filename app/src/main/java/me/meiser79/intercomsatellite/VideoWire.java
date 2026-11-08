package me.meiser79.intercomsatellite;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One video frame on the wire: a 9 byte header (flags, width, height, capture time in ms, all big
 * endian) followed by one H.264 access unit in Annex B form. Bit 0 of the flags marks a key frame,
 * which carries its SPS and PPS so any frame after a gap can start a decoder.
 */
final class VideoWire {
    static final int HEADER = 9;
    static final int KEY = 1;
    static final int NAL_IDR = 5, NAL_SPS = 7, NAL_PPS = 8;
    private static final byte[] START = {0, 0, 0, 1};

    private VideoWire() {}

    static boolean isKey(byte[] frame) {
        return frame != null && frame.length > 0 && (frame[0] & KEY) != 0;
    }

    static int width(byte[] frame) {
        return ((frame[1] & 255) << 8) | (frame[2] & 255);
    }

    static int height(byte[] frame) {
        return ((frame[3] & 255) << 8) | (frame[4] & 255);
    }

    static long timeMs(byte[] frame) {
        return ((long) (frame[5] & 255) << 24)
                | ((frame[6] & 255) << 16)
                | ((frame[7] & 255) << 8)
                | (frame[8] & 255);
    }

    /** The Annex B payload after the header. */
    static byte[] payload(byte[] frame) {
        return Arrays.copyOfRange(frame, HEADER, frame.length);
    }

    static int type(byte[] nal) {
        return nal.length == 0 ? -1 : nal[0] & 0x1f;
    }

    /** Builds a frame. Parameter sets in {@code units} are dropped; key frames get {@code sps}/{@code pps}. */
    static byte[] pack(
            boolean key, int width, int height, long timeMs, byte[] sps, byte[] pps, List<byte[]> units) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(key ? KEY : 0);
        out.write(width >> 8);
        out.write(width);
        out.write(height >> 8);
        out.write(height);
        out.write((int) (timeMs >> 24));
        out.write((int) (timeMs >> 16));
        out.write((int) (timeMs >> 8));
        out.write((int) timeMs);
        if (key) {
            out.write(START, 0, START.length);
            out.write(sps, 0, sps.length);
            out.write(START, 0, START.length);
            out.write(pps, 0, pps.length);
        }
        for (byte[] unit : units) {
            int t = type(unit);
            if (t < 0 || t == NAL_SPS || t == NAL_PPS) continue;
            out.write(START, 0, START.length);
            out.write(unit, 0, unit.length);
        }
        return out.toByteArray();
    }

    /** Splits Annex B data (3 or 4 byte start codes) into NAL units without their start codes. */
    static List<byte[]> nals(byte[] data, int offset, int length) {
        List<byte[]> units = new ArrayList<>();
        int end = offset + length, start = -1, i = offset;
        while (i + 2 < end) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                if (start >= 0) units.add(trim(data, start, i));
                i += 3;
                start = i;
            } else i++;
        }
        if (start >= 0 && start < end) units.add(Arrays.copyOfRange(data, start, end));
        else if (start < 0 && length > 0)
            units.add(Arrays.copyOfRange(data, offset, end));
        return units;
    }

    static List<byte[]> nals(byte[] data) {
        return nals(data, 0, data.length);
    }

    /** Drops the zero byte a 4 byte start code leaves at the end of the unit before it. */
    private static byte[] trim(byte[] data, int from, int to) {
        while (to > from && data[to - 1] == 0) to--;
        return Arrays.copyOfRange(data, from, to);
    }

    static byte[] find(List<byte[]> units, int type) {
        for (byte[] unit : units) if (type(unit) == type) return unit;
        return null;
    }
}
