package me.meiser79.intercomsatellite;

import org.json.JSONObject;

/**
 * The video socket of a call, in both directions. A WebSocket buffers whatever it is given, so on a
 * slow link video would fall further and further behind. The far side acknowledges each frame:
 * past {@link #MAX_IN_FLIGHT} unacknowledged frames this side drops, then waits for a key frame so
 * the decoder never sees a gap.
 */
final class VideoLink {
    interface Events {
        void frame(byte[] frame);

        void message(JSONObject message);
    }

    static final int MAX_IN_FLIGHT = 6;

    final IntercomServer.Link link;
    private final Events events;
    private int sent, acked, received, dropped;
    private boolean waitingKey;

    VideoLink(IntercomServer.Link link, Events events) {
        this.link = link;
        this.events = events;
    }

    /** One message from the socket. */
    void receive(String text, byte[] bytes) {
        if (bytes != null) {
            int n;
            synchronized (this) {
                n = ++received;
            }
            send(Wire.obj("type", "ack", "n", n));
            events.frame(bytes);
            return;
        }
        if (text == null) return;
        JSONObject message;
        try {
            message = new JSONObject(text);
        } catch (Exception e) {
            return;
        }
        if (message.optString("type").equals("ack")) {
            int n = message.optInt("n", -1);
            synchronized (this) {
                if (n > acked && n <= sent) acked = n;
            }
            return;
        }
        events.message(message);
    }

    void send(JSONObject message) {
        try {
            link.text(message.toString());
        } catch (RuntimeException ignored) {
        }
    }

    /** Sends {@code frame} unless the link has fallen behind. */
    void sendFrame(byte[] frame) {
        boolean key = VideoWire.isKey(frame);
        synchronized (this) {
            if (sent - acked >= MAX_IN_FLIGHT || (waitingKey && !key)) {
                waitingKey = true;
                dropped++;
                return;
            }
            waitingKey = false;
            sent++;
        }
        try {
            link.bytes(frame);
        } catch (RuntimeException ignored) {
        }
    }

    synchronized int sent() {
        return sent;
    }

    synchronized int dropped() {
        return dropped;
    }

    void close() {
        link.close();
    }
}
