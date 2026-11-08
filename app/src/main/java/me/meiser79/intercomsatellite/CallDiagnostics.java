package me.meiser79.intercomsatellite;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** Only local technical events. No peer names, addresses, keys, tokens or audio. */
final class CallDiagnostics {
    private final Deque<String> events = new ArrayDeque<>();
    private long started = System.nanoTime();
    private String version = "dev";

    void setVersion(String version) {
        this.version = version;
    }

    synchronized void clear() {
        events.clear();
        started = System.nanoTime();
    }

    synchronized void event(String event) {
        if (events.size() == 60) events.removeFirst();
        String line =
                String.format(
                        Locale.ROOT,
                        "+%.2fs %s",
                        (System.nanoTime() - started) / 1e9,
                        event);
        events.addLast(line);
        // The same technical events in logcat, so a call that ends early can be traced there.
        Log.i("IntercomSatellite", line);
    }

    void error(String stage, Throwable error) {
        // Exception messages can contain signed URLs. Record types only.
        StringBuilder types = new StringBuilder();
        for (int i = 0; error != null && i < 4; i++, error = error.getCause()) {
            if (i > 0) types.append(" / ");
            types.append(error.getClass().getSimpleName());
        }
        event(stage + ": " + types);
    }

    synchronized String report() {
        return "Intercom Satellite " + version + "\n" + String.join("\n", events);
    }
}
