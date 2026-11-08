package me.meiser79.intercomsatellite;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import okhttp3.*;

import org.json.JSONObject;
import org.junit.*;

import java.util.concurrent.*;

/** Video signalling between this phone and a kiosk, with the camera and decoder replaced. */
public class EngineVideoTest {
    Config config;
    AudioPipe audio;
    VideoPipe video;
    Engine engine;
    Wire kioskWire = new Wire("shared");
    VideoLinkTest.FakeLink socket = new VideoLinkTest.FakeLink();

    @Before
    public void setup() {
        config = mock(Config.class);
        when(config.key()).thenReturn("shared");
        when(config.id()).thenReturn("phone");
        when(config.name()).thenReturn("Phone");
        when(config.video()).thenReturn(true);
        when(config.videoQuality()).thenReturn("medium");
        audio = mock(AudioPipe.class);
        video = mock(VideoPipe.class);
        when(video.hasCamera()).thenReturn(true);
        engine = new Engine(null, new TestMessages(), config, () -> {}, audio, video);
        engine.running = true;
    }

    @After
    public void cleanup() {
        engine.stop();
    }

    JSONObject invite(String id, JSONObject offer) throws Exception {
        JSONObject body =
                Wire.obj(
                        "call",
                        id,
                        "kind",
                        "call",
                        "from",
                        Wire.obj("id", "kitchen", "name", "Kitchen", "port", 2324));
        if (offer != null) body.put("video", offer);
        return engine.incoming(body, kioskWire.token(id, "kitchen"), "127.0.0.1");
    }

    @Test
    public void identityAdvertisesVideoCalls() throws Exception {
        assertTrue(engine.identity().getBoolean("video"));
        when(config.video()).thenReturn(false);
        assertFalse(engine.identity().getBoolean("video"));
    }

    @Test
    public void ringingWithTheCallersCameraShowsItsPicture() throws Exception {
        JSONObject reply = invite("call-1", Wire.obj("camera", true));
        assertEquals("ringing", reply.getString("status"));
        assertTrue(reply.getBoolean("video"));
        assertTrue(engine.peerVideo);
        assertTrue(engine.farCamera);
        verify(video).openFar();
        assertFalse(engine.cameraOn);
    }

    @Test
    public void voiceOnlyWhenVideoIsOffHere() throws Exception {
        when(config.video()).thenReturn(false);
        JSONObject reply = invite("call-1", Wire.obj("camera", true));
        assertFalse(reply.getBoolean("video"));
        assertFalse(engine.peerVideo);
        assertFalse(engine.allowVideo("call-1", kioskWire.token("call-1", "kitchen"), "127.0.0.1"));
        assertFalse(engine.camera(true));
        verify(video, never()).openFar();
    }

    @Test
    public void onlyTheCallersTokenOpensTheVideoSocket() throws Exception {
        invite("call-1", Wire.obj("camera", false));
        assertTrue(engine.allowVideo("call-1", kioskWire.token("call-1", "kitchen"), "127.0.0.1"));
        assertFalse(engine.allowVideo("call-1", kioskWire.token("call-1", "hall"), "127.0.0.1"));
        assertFalse(engine.allowVideo("call-2", kioskWire.token("call-2", "kitchen"), "127.0.0.1"));
        assertFalse(
                engine.allowVideo("call-1", new Wire("other").token("call-1", "kitchen"), "127.0.0.1"));
    }

    @Test
    public void videoSocketCarriesCameraStateFramesAndKeyRequests() throws Exception {
        invite("call-1", Wire.obj("camera", false));
        engine.attachVideo("call-1", socket);
        assertEquals("{\"type\":\"camera\",\"on\":false}", socket.texts.get(0));

        engine.videoMessage("call-1", socket, "{\"type\":\"camera\",\"on\":true}", null);
        assertTrue(engine.farCamera);
        verify(video).openFar();
        byte[] frame = VideoLinkTest.frame(true);
        engine.videoMessage("call-1", socket, null, frame);
        verify(video).receive(frame);
        assertTrue(engine.lastFarFrame > 0);

        engine.videoMessage("call-1", socket, "{\"type\":\"key\"}", null);
        verify(video).keyFrame();

        engine.videoMessage("call-1", socket, "{\"type\":\"camera\",\"on\":false}", null);
        assertFalse(engine.farCamera);
        verify(video).closeFar();
    }

    @Test
    public void cameraOnlyGoesOnWhenAskedAndTellsTheOtherSide() throws Exception {
        invite("call-1", Wire.obj("camera", false));
        engine.attachVideo("call-1", socket);
        assertTrue(engine.camera(true));
        assertTrue(engine.cameraOn);
        verify(video).startCamera("medium");
        assertEquals("{\"type\":\"camera\",\"on\":true}", socket.texts.get(1));
        assertTrue(engine.camera(false));
        verify(video).stopCamera();
        assertEquals("{\"type\":\"camera\",\"on\":false}", socket.texts.get(2));
    }

    @Test
    public void noCameraNoSending() throws Exception {
        when(video.hasCamera()).thenReturn(false);
        invite("call-1", Wire.obj("camera", true));
        assertTrue(engine.peerVideo);
        assertFalse(engine.camera(true));
        verify(video, never()).startCamera(anyString());
    }

    @Test
    public void announcementsNeverCarryVideo() throws Exception {
        when(config.acceptAnnouncements()).thenReturn(true);
        JSONObject body =
                Wire.obj(
                        "call",
                        "b-1",
                        "kind",
                        "broadcast",
                        "from",
                        Wire.obj("id", "kitchen", "name", "Kitchen", "port", 2324),
                        "video",
                        Wire.obj("camera", true));
        engine.incoming(body, kioskWire.token("b-1", "kitchen"), "127.0.0.1");
        assertFalse(engine.peerVideo);
        assertFalse(engine.camera(true));
    }

    /** Answers in the phone app, against a kiosk that accepts the answer. */
    interface Check {
        void run() throws Exception;
    }

    void answerInPhoneApp(boolean withVideo, Check check) throws Exception {
        okhttp3.mockwebserver.MockWebServer kiosk = new okhttp3.mockwebserver.MockWebServer();
        kiosk.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("{\"ok\":true}"));
        kiosk.start();
        try {
            JSONObject body =
                    Wire.obj(
                            "call",
                            "call-1",
                            "kind",
                            "call",
                            "from",
                            Wire.obj("id", "kitchen", "name", "Kitchen", "port", kiosk.getPort()),
                            "video",
                            Wire.obj("camera", true));
            engine.incoming(body, kioskWire.token("call-1", "kitchen"), "127.0.0.1");
            engine.phoneManaged = true;
            PhoneCallSession session =
                    new PhoneCallSession(
                            engine,
                            "call-1",
                            () -> {},
                            new PhoneCallSession.Display() {
                                public void accepted() {}

                                public void closed(int cause) {}
                            },
                            true);
            session.answer(withVideo);
            assertEquals("connecting", engine.state);
            assertNotNull(kiosk.takeRequest(10, TimeUnit.SECONDS));
            check.run();
        } finally {
            kiosk.shutdown();
        }
    }

    @Test
    public void answeringWithVideoInThePhoneAppTurnsTheCameraOn() throws Exception {
        answerInPhoneApp(
                true,
                () -> {
                    assertTrue(engine.cameraOn);
                    verify(video).startCamera("medium");
                });
    }

    @Test
    public void answeringByVoiceInThePhoneAppLeavesTheCameraOff() throws Exception {
        answerInPhoneApp(
                false,
                () -> {
                    assertFalse(engine.cameraOn);
                    assertTrue(engine.farCamera);
                    verify(video, never()).startCamera(anyString());
                });
    }

    @Test
    public void endingTheCallStopsVideoAndClosesItsSocket() throws Exception {
        invite("call-1", Wire.obj("camera", true));
        engine.attachVideo("call-1", socket);
        engine.camera(true);
        engine.finish("Call ended", true);
        assertTrue(socket.closed);
        assertFalse(engine.peerVideo);
        assertFalse(engine.cameraOn);
        assertFalse(engine.farCamera);
        verify(video, atLeastOnce()).stop();
    }

    @Test
    public void callerOpensTheVideoSocketWhileTheKioskRings() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        BlockingQueue<JSONObject> offers = new LinkedBlockingQueue<>();
        IntercomServer kiosk =
                new IntercomServer(
                        0,
                        new IntercomServer.Listener() {
                            public JSONObject identity() {
                                return Wire.obj("id", "kiosk");
                            }

                            public JSONObject incoming(JSONObject b, String t, String h) {
                                offers.add(b.optJSONObject("video"));
                                return Wire.obj("status", "ringing", "video", true);
                            }

                            public JSONObject signal(String id, JSONObject b, String t, String h) {
                                return Wire.obj("ok", true);
                            }

                            public boolean allowAudio(String id, String t, String h) {
                                return false;
                            }

                            public void attach(String id, IntercomServer.Link l) {}

                            public void message(String id, IntercomServer.Link l, String t, byte[] b) {}

                            public void disconnected(String id, IntercomServer.Link l) {}

                            public boolean allowVideo(String id, String t, String h) {
                                return kioskWire.verify(t, id) != null;
                            }

                            public void attachVideo(String id, IntercomServer.Link l) {
                                opened.countDown();
                            }

                            public void videoMessage(
                                    String id, IntercomServer.Link l, String t, byte[] b) {
                                if (t != null) received.add(t);
                            }
                        },
                        false,
                        false);
        kiosk.start(15000, true);
        try {
            Peer target = new Peer("127.0.0.1", kiosk.getListeningPort(), false);
            target.id = "kiosk";
            target.name = "Kiosk";
            target.ready = true;
            target.video = true;
            engine.call(target, true);
            JSONObject offer = offers.poll(10, TimeUnit.SECONDS);
            assertNotNull(offer);
            assertTrue(offer.getBoolean("camera"));
            assertTrue(opened.await(10, TimeUnit.SECONDS));
            assertEquals("{\"type\":\"camera\",\"on\":true}", received.poll(10, TimeUnit.SECONDS));
            assertTrue(engine.peerVideo);
            assertTrue(engine.cameraOn);
            verify(video).startCamera("medium");
        } finally {
            kiosk.stop();
        }
    }
}
