package dev.antiafk.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers when each kind of activity (look, move, break, ...) last earned credit, so credit can be
 * taken back from one kind once it turns out to be automated, without touching the others.
 * The player's last activity is the newest credit of any kind.
 */
public final class ActivityLedger {

    private final Map<String, Long> credits = new HashMap<>();
    private volatile long lastActivity;

    public void credit(String kind, long time) {
        credits.put(kind, time);
        if (time > lastActivity) lastActivity = time;
    }

    /**
     * Takes back the credit {@code kind} earned after {@code since}.
     * @return true if the last activity moved back
     */
    public boolean rewind(String kind, long since) {
        Long current = credits.get(kind);
        if (current == null || current <= since) return false;
        credits.put(kind, since);
        long newest = 0;
        for (long t : credits.values()) newest = Math.max(newest, t);
        boolean moved = newest < lastActivity;
        lastActivity = newest;
        return moved;
    }

    public long lastActivity() {
        return lastActivity;
    }
}
