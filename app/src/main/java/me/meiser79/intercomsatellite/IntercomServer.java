package me.meiser79.intercomsatellite;

import fi.iki.elonen.NanoWSD;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

final class IntercomServer extends NanoWSD {
    interface Listener {
        JSONObject identity();

        JSONObject incoming(JSONObject body, String token, String host);

        JSONObject signal(String id, JSONObject body, String token, String host);

        boolean allowAudio(String id, String token, String host);

        void attach(String id, Link link);

        void message(String id, Link link, String text, byte[] bytes);

        void disconnected(String id, Link link);

        default void httpEvent(String event) {}

        /** Whether a token opens the video socket of the call named. */
        default boolean allowVideo(String id, String token, String host) {
            return false;
        }

        default void attachVideo(String id, Link link) {
            link.close();
        }

        default void videoMessage(String id, Link link, String text, byte[] bytes) {}

        default void videoDisconnected(String id, Link link) {}
    }

    interface Link {
        void text(String text);

        void bytes(byte[] bytes);

        void close();

        void ping();
    }

    private static final String AUDIO_PREFIX = "/api/intercom/audio/",
            VIDEO_PREFIX = "/api/intercom/video/";

    private final Listener listener;
    private final boolean secure;
    private final boolean requireTls;
    private final Map<String, Long> identityTimes = new HashMap<>();

    IntercomServer(int port, Listener listener, boolean secure, boolean requireTls) {
        super(port);
        this.listener = listener;
        this.secure = secure;
        this.requireTls = requireTls;
    }

    static Response json(int code, JSONObject j) {
        Response.IStatus status = Response.Status.lookup(code);
        if (status == null)
            status =
                    new Response.IStatus() {
                        public int getRequestStatus() {
                            return code;
                        }

                        public String getDescription() {
                            return code + " Intercom";
                        }
                    };
        Response r = newFixedLengthResponse(status, "application/json", j.toString());
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    @Override
    public Response serve(IHTTPSession s) {
        String path = s.getUri();
        if (path.equals("/api/intercom/identity") && s.getMethod() == Method.GET) {
            synchronized (identityTimes) {
                long now = System.currentTimeMillis();
                identityTimes.entrySet().removeIf(e -> e.getValue() < now - 1000);
                if (identityTimes.containsKey(s.getRemoteIpAddress()))
                    return json(429, Wire.obj("error", "too many probes"));
                if (identityTimes.size() > 512) return json(503, Wire.obj("error", "busy"));
                identityTimes.put(s.getRemoteIpAddress(), now);
            }
            return json(200, listener.identity());
        }
        if (requireTls && !secure) return json(426, Wire.obj("status", "tls"));
        if (path.startsWith(AUDIO_PREFIX)) {
            String id = path.substring(AUDIO_PREFIX.length()), token = s.getParms().get("token");
            if (!Wire.validCallId(id)
                    || !"websocket".equalsIgnoreCase(s.getHeaders().get("upgrade"))
                    || !listener.allowAudio(id, token, s.getRemoteIpAddress()))
                return json(403, Wire.obj("error", "refused"));
            return super.serve(s);
        }
        if (path.startsWith(VIDEO_PREFIX)) {
            String id = path.substring(VIDEO_PREFIX.length()), token = s.getParms().get("token");
            if (!Wire.validCallId(id)
                    || !"websocket".equalsIgnoreCase(s.getHeaders().get("upgrade"))
                    || !listener.allowVideo(id, token, s.getRemoteIpAddress()))
                return json(403, Wire.obj("error", "refused"));
            return super.serve(s);
        }
        if (s.getMethod() != Method.POST) return json(404, Wire.obj("error", "not found"));
        if (!path.equals("/api/intercom/call") && !path.startsWith("/api/intercom/call/"))
            return json(404, Wire.obj("error", "not found"));
        try {
            listener.httpEvent("Call signal received over HTTP");
            JSONObject body = new JSONObject(HttpBody.read(s.getInputStream(), s.getHeaders()));
            String auth = s.getHeaders().getOrDefault("authorization", "");
            String token = auth.startsWith("Bearer ") ? auth.substring(7) : "";
            JSONObject answer;
            if (path.equals("/api/intercom/call"))
                answer = listener.incoming(body, token, s.getRemoteIpAddress());
            else if (path.startsWith("/api/intercom/call/"))
                answer =
                        listener.signal(
                                path.substring("/api/intercom/call/".length()),
                                body,
                                token,
                                s.getRemoteIpAddress());
            else return json(404, Wire.obj("error", "not found"));
            int code = answer.optInt("code", 200);
            listener.httpEvent("Call signal answered (HTTP " + code + ")");
            return postResponse(code, answer);
        } catch (HttpBody.Invalid e) {
            listener.httpEvent("Call signal rejected: HTTP body format (HTTP " + e.code + ")");
            return postResponse(e.code, Wire.obj("error", "invalid body"));
        } catch (Exception e) {
            listener.httpEvent("Call signal rejected: unreadable request (HTTP 400)");
            return postResponse(400, Wire.obj("error", "invalid request"));
        }
    }

    private static Response postResponse(int code, JSONObject body) {
        Response r = json(code, body);
        r.closeConnection(true);
        return r;
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession s) {
        String uri = s.getUri();
        return uri.startsWith(VIDEO_PREFIX)
                ? new IncomingSocket(s, uri.substring(VIDEO_PREFIX.length()), true)
                : new IncomingSocket(s, uri.substring(AUDIO_PREFIX.length()), false);
    }

    private final class IncomingSocket extends WebSocket implements Link {
        // Video paces itself with acknowledgements (VideoLink); this only stops a runaway backlog.
        private static final int MAX_QUEUED_AUDIO_FRAMES = 6, MAX_QUEUED_VIDEO_FRAMES = 24;

        final String id;
        final boolean video;
        // NanoWSD writes to the socket synchronously. Engine calls (UI thread, telecom callbacks)
        // must not do network I/O, so all writes run in order on this thread.
        private final ExecutorService sender =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "intercom-ws-send");
                            t.setDaemon(true);
                            return t;
                        });
        private final AtomicInteger queuedAudioFrames =
                new AtomicInteger();

        IncomingSocket(IHTTPSession s, String id, boolean video) {
            super(s);
            this.id = id;
            this.video = video;
        }

        protected void onOpen() {
            if (video) listener.attachVideo(id, this);
            else listener.attach(id, this);
        }

        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean remote) {
            sender.shutdown();
            failed();
        }

        protected void onMessage(WebSocketFrame frame) {
            String text = null;
            byte[] bytes = null;
            if (frame.getOpCode() == WebSocketFrame.OpCode.Binary) bytes = frame.getBinaryPayload();
            else if (frame.getOpCode() == WebSocketFrame.OpCode.Text) text = frame.getTextPayload();
            else return;
            if (video) listener.videoMessage(id, this, text, bytes);
            else listener.message(id, this, text, bytes);
        }

        protected void onPong(WebSocketFrame frame) {}

        protected void onException(IOException e) {
            sender.shutdown();
            failed();
        }

        /** Queues a frame write; never blocks or touches the network on the calling thread. */
        private void enqueue(Runnable write) {
            try {
                sender.execute(write);
            } catch (RejectedExecutionException closed) {
                // Socket already shut down; nothing left to send.
            }
        }

        private void failed() {
            if (video) listener.videoDisconnected(id, this);
            else listener.disconnected(id, this);
        }

        public void text(String s) {
            enqueue(
                    () -> {
                        try {
                            send(s);
                        } catch (IOException e) {
                            failed();
                        }
                    });
        }

        public void bytes(byte[] b) {
            // Drop audio instead of building a backlog when the peer reads slowly.
            if (queuedAudioFrames.incrementAndGet()
                    > (video ? MAX_QUEUED_VIDEO_FRAMES : MAX_QUEUED_AUDIO_FRAMES)) {
                queuedAudioFrames.decrementAndGet();
                return;
            }
            enqueue(
                    () -> {
                        try {
                            send(b);
                        } catch (IOException e) {
                            failed();
                        } finally {
                            queuedAudioFrames.decrementAndGet();
                        }
                    });
        }

        public void ping() {
            enqueue(
                    () -> {
                        try {
                            ping(new byte[0]);
                        } catch (IOException e) {
                            failed();
                        }
                    });
        }

        public void close() {
            enqueue(
                    () -> {
                        try {
                            close(WebSocketFrame.CloseCode.NormalClosure, "ended", false);
                        } catch (IOException ignored) {
                        } finally {
                            sender.shutdown();
                        }
                    });
        }
    }
}
