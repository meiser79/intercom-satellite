package me.meiser79.intercomsatellite;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Discovery {
    private final NsdManager nsd;
    private final Engine engine;
    private WifiManager.MulticastLock multicast;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private final Queue<NsdServiceInfo> queue = new ArrayDeque<>();
    /** Services seen on the network, by name, resolved again now and then. */
    private final Map<String, NsdServiceInfo> known = new HashMap<>();
    private boolean resolving, stopped;

    Discovery(Context c, Engine e) {
        engine = e;
        nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi =
                (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) multicast = wifi.createMulticastLock("IntercomSatellite-discovery");
    }

    void start() {
        if (multicast != null) {
            multicast.setReferenceCounted(false);
            multicast.acquire();
        }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceType("_kiosk-satellite._tcp.");
        info.setServiceName("ks-" + engine.config.id());
        info.setPort(2324);
        info.setAttribute("id", engine.config.id());
        info.setAttribute("name", engine.config.name());
        info.setAttribute("version", engine.appVersion);
        info.setAttribute("port", "2324");
        registration =
                new NsdManager.RegistrationListener() {
                    public void onRegistrationFailed(NsdServiceInfo i, int e) {
                        engine.detail = engine.msg.get(R.string.err_visibility_failed);
                        engine.changed();
                    }

                    public void onUnregistrationFailed(NsdServiceInfo i, int e) {}

                    public void onServiceRegistered(NsdServiceInfo i) {}

                    public void onServiceUnregistered(NsdServiceInfo i) {}
                };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        discovery =
                new NsdManager.DiscoveryListener() {
                    public void onDiscoveryStarted(String s) {}

                    public void onDiscoveryStopped(String s) {}

                    public void onStartDiscoveryFailed(String s, int e) {
                        engine.detail = engine.msg.get(R.string.err_discovery_failed);
                        engine.changed();
                    }

                    public void onStopDiscoveryFailed(String s, int e) {}

                    public void onServiceLost(NsdServiceInfo i) {
                        synchronized (Discovery.this) {
                            known.remove(i.getServiceName());
                        }
                    }

                    public void onServiceFound(NsdServiceInfo i) {
                        if (i.getServiceName().equals("ks-" + engine.config.id())) return;
                        synchronized (Discovery.this) {
                            known.put(i.getServiceName(), i);
                        }
                        enqueue(i);
                    }
                };
        nsd.discoverServices("_kiosk-satellite._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    /**
     * Resolves the known services again. Android reports a service once, so a device that comes
     * back at another address or port, or after it was dropped as unreachable, shows up only
     * this way.
     */
    synchronized void again() {
        for (NsdServiceInfo i : known.values()) {
            boolean waiting = false;
            for (NsdServiceInfo q : queue)
                waiting |= q.getServiceName().equals(i.getServiceName());
            if (!waiting) enqueue(i);
        }
    }

    synchronized void enqueue(NsdServiceInfo i) {
        if (stopped || queue.size() > 64) return;
        queue.add(i);
        next();
    }

    synchronized void next() {
        if (stopped || resolving || queue.isEmpty()) return;
        resolving = true;
        nsd.resolveService(
                queue.remove(),
                new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo i, int e) {
                        done();
                    }

                    public void onServiceResolved(NsdServiceInfo i) {
                        if (!stopped && i.getHost() != null) {
                            Peer p =
                                    new Peer(
                                            i.getHost().getHostAddress(),
                                            i.getPort(),
                                            "1".equals(attr(i, "tls")));
                            p.id = attr(i, "id");
                            p.name = attr(i, "name");
                            engine.found(p);
                        }
                        done();
                    }

                    void done() {
                        synchronized (Discovery.this) {
                            resolving = false;
                            next();
                        }
                    }
                });
    }

    String attr(NsdServiceInfo i, String k) {
        byte[] b = i.getAttributes().get(k);
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    void stop() {
        stopped = true;
        try {
            if (discovery != null) nsd.stopServiceDiscovery(discovery);
        } catch (Exception ignored) {
        }
        try {
            if (registration != null) nsd.unregisterService(registration);
        } catch (Exception ignored) {
        }
        if (multicast != null && multicast.isHeld()) multicast.release();
    }

    /**
     * The phone's address on the local network: Wi-Fi or Ethernet first, otherwise a VPN that
     * may lead home. Mobile data alone has none, since kiosks cannot reach it.
     */
    static String localIp() {
        String local = address("wlan", "eth");
        return local.isEmpty() ? address("tun", "wg", "ppp", "ipsec") : local;
    }

    private static String address(String... prefixes) {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (String prefix : prefixes) {
                    if (!n.getName().startsWith(prefix)) continue;
                    for (InetAddress a : Collections.list(n.getInetAddresses()))
                        if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
