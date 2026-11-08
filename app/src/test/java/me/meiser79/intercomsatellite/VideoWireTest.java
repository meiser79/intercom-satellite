package me.meiser79.intercomsatellite;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class VideoWireTest {
    static final byte[] SPS = {0x67, 1, 2}, PPS = {0x68, 3}, IDR = {0x65, 4, 5}, SLICE = {0x41, 6};

    @Test
    public void headerCarriesFlagsSizeAndTimeBigEndian() {
        byte[] frame = VideoWire.pack(false, 1280, 720, 0x01020304L, SPS, PPS, List.of(SLICE));
        assertEquals(0, frame[0]);
        assertEquals(1280, VideoWire.width(frame));
        assertEquals(720, VideoWire.height(frame));
        assertEquals(0x01020304L, VideoWire.timeMs(frame));
        assertArrayEquals(new byte[] {0, 0, 0, 1, 0x41, 6}, VideoWire.payload(frame));
        assertFalse(VideoWire.isKey(frame));
    }

    @Test
    public void keyFrameLeadsWithItsParameterSetsOnce() {
        // An encoder that repeats SPS/PPS inline must not send them twice.
        byte[] frame = VideoWire.pack(true, 480, 640, 7, SPS, PPS, List.of(SPS, PPS, IDR));
        assertTrue(VideoWire.isKey(frame));
        List<byte[]> units = VideoWire.nals(VideoWire.payload(frame));
        assertEquals(3, units.size());
        assertArrayEquals(SPS, units.get(0));
        assertArrayEquals(PPS, units.get(1));
        assertArrayEquals(IDR, units.get(2));
    }

    @Test
    public void splitsThreeAndFourByteStartCodes() {
        byte[] data = {0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 3};
        List<byte[]> units = VideoWire.nals(data);
        assertEquals(3, units.size());
        assertArrayEquals(new byte[] {0x67, 1}, units.get(0));
        assertArrayEquals(new byte[] {0x68, 2}, units.get(1));
        assertArrayEquals(new byte[] {0x65, 3}, units.get(2));
        assertArrayEquals(new byte[] {0x68, 2}, VideoWire.find(units, VideoWire.NAL_PPS));
        assertNull(VideoWire.find(Arrays.asList(SLICE), VideoWire.NAL_IDR));
    }
}
