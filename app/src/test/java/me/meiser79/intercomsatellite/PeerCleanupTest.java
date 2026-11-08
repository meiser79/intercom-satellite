package me.meiser79.intercomsatellite;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.json.JSONObject;
import org.junit.*;

import java.net.ServerSocket;

/** Found devices that moved or went away leave the list; devices added by hand stay. */
public class PeerCleanupTest {
    Engine engine;
    IntercomServer kiosk;
    int closedPort;

    @Before
    public void setup() throws Exception {
        Config config = mock(Config.class);
        when(config.key()).thenReturn("shared");
        when(config.id()).thenReturn("phone");
        engine = new Engine(null, new TestMessages(), config, () -> {}, mock(AudioPipe.class));
        engine.running = true;
        kiosk =
                new IntercomServer(
                        0,
                        new IntercomServer.Listener() {
                            public JSONObject identity() {
                                return Wire.obj(
                                        "id", "kiosk", "name", "Kitchen", "enabled", true,
                                        "key", Wire.fingerprint("shared"));
                            }

                            public JSONObject incoming(JSONObject b, String t, String h) {
                                return Wire.obj("status", "off");
                            }

                            public JSONObject signal(String id, JSONObject b, String t, String h) {
                                return Wire.obj("ok", false);
                            }

                            public boolean allowAudio(String id, String t, String h) {
                                return false;
                            }

                            public void attach(String id, IntercomServer.Link l) {}

                            public void message(
                                    String id, IntercomServer.Link l, String t, byte[] b) {}

                            public void disconnected(String id, IntercomServer.Link l) {}
                        },
                        false,
                        false);
        kiosk.start(15000, true);
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
    }

    @After
    public void tearDown() {
        engine.running = false;
        kiosk.stop();
        engine.io.shutdownNow();
        engine.timer.shutdownNow();
    }

    private Peer peer(int port, String id, boolean manual, long seen) {
        Peer p = new Peer("127.0.0.1", port, false);
        p.id = id;
        p.manual = manual;
        p.seen = seen;
        engine.peers.put(p.addressKey(), p);
        return p;
    }

    @Test
    public void aDeviceAtANewAddressReplacesItsOldEntry() {
        long now = System.currentTimeMillis();
        Peer old = peer(closedPort, "kiosk", false, now);
        Peer added = peer(closedPort + 1, "kiosk", true, 0);
        Peer current = peer(kiosk.getListeningPort(), "", false, now);
        engine.probe(current);
        assertTrue(current.ready);
        assertFalse(engine.peers.containsKey(old.addressKey()));
        assertTrue(engine.peers.containsKey(added.addressKey()));
    }

    @Test
    public void aFoundDeviceThatStopsAnsweringIsDropped() {
        long now = System.currentTimeMillis();
        Peer gone = peer(closedPort, "kiosk", false, now - Engine.STALE_MS - 1000);
        Peer recent = peer(closedPort + 1, "door", false, now);
        Peer added = peer(closedPort + 2, "garden", true, 0);
        engine.probe(gone);
        engine.probe(recent);
        engine.probe(added);
        assertFalse(engine.peers.containsKey(gone.addressKey()));
        assertEquals(Peer.Status.UNREACHABLE, recent.status);
        assertTrue(engine.peers.containsKey(recent.addressKey()));
        assertTrue(engine.peers.containsKey(added.addressKey()));
    }
}
