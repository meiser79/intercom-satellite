package me.meiser79.intercomsatellite;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class VideoLinkTest {
    static final class FakeLink implements IntercomServer.Link {
        final List<String> texts = new ArrayList<>();
        final List<byte[]> bytes = new ArrayList<>();
        boolean closed;

        public void text(String t) {
            texts.add(t);
        }

        public void bytes(byte[] b) {
            bytes.add(b);
        }

        public void close() {
            closed = true;
        }

        public void ping() {}
    }

    final FakeLink socket = new FakeLink();
    final List<byte[]> frames = new ArrayList<>();
    final List<JSONObject> messages = new ArrayList<>();
    final VideoLink link =
            new VideoLink(
                    socket,
                    new VideoLink.Events() {
                        public void frame(byte[] f) {
                            frames.add(f);
                        }

                        public void message(JSONObject m) {
                            messages.add(m);
                        }
                    });

    static byte[] frame(boolean key) {
        return new byte[] {(byte) (key ? 1 : 0), 0, 16, 0, 16, 0, 0, 0, 0, 0x41};
    }

    @Test
    public void acknowledgesEachFrameReceived() throws Exception {
        link.receive(null, frame(true));
        link.receive(null, frame(false));
        assertEquals(2, frames.size());
        assertEquals(2, socket.texts.size());
        JSONObject ack = new JSONObject(socket.texts.get(1));
        assertEquals("ack", ack.getString("type"));
        assertEquals(2, ack.getInt("n"));
    }

    @Test
    public void fallsBackToTheNextKeyFrameWhenTooFarBehind() {
        link.sendFrame(frame(true));
        for (int i = 1; i < VideoLink.MAX_IN_FLIGHT; i++) link.sendFrame(frame(false));
        assertEquals(VideoLink.MAX_IN_FLIGHT, socket.bytes.size());
        link.sendFrame(frame(false));
        assertEquals(1, link.dropped());
        // Acknowledged, but the decoder would see a gap: only a key frame goes next.
        link.receive("{\"type\":\"ack\",\"n\":6}", null);
        link.sendFrame(frame(false));
        assertEquals(VideoLink.MAX_IN_FLIGHT, socket.bytes.size());
        link.sendFrame(frame(true));
        assertEquals(VideoLink.MAX_IN_FLIGHT + 1, socket.bytes.size());
        link.sendFrame(frame(false));
        assertEquals(VideoLink.MAX_IN_FLIGHT + 2, socket.bytes.size());
    }

    @Test
    public void ignoresAcknowledgementsOfFramesNeverSent() {
        link.receive("{\"type\":\"ack\",\"n\":100}", null);
        for (int i = 0; i < VideoLink.MAX_IN_FLIGHT + 1; i++) link.sendFrame(frame(i == 0));
        assertEquals(VideoLink.MAX_IN_FLIGHT, socket.bytes.size());
    }

    @Test
    public void passesOtherMessagesOnAndSkipsGarbage() throws Exception {
        link.receive("{\"type\":\"camera\",\"on\":true}", null);
        link.receive("not json", null);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).getBoolean("on"));
        assertTrue(socket.texts.isEmpty());
    }
}
