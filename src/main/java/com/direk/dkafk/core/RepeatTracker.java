package com.direk.dkafk.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Stops counting an action once it has been repeated non-stop for too long.
 * <p>
 * Each action has a key describing what was done and where, e.g. {@code break:12,64,-3} for breaking
 * the oneblock, or {@code chat:<message>}. While the same key keeps happening (with gaps no longer than
 * {@code resetAfterMillis}), it counts for {@code maxStreakMillis}, then stops counting. A real player
 * doing the same thing will also look around or move, which counts separately.
 */
public final class RepeatTracker {

    private final long maxStreakMillis;
    private final long resetAfterMillis;
    /** key -> {streak start, last seen} */
    private final Map<String, long[]> streaks = new HashMap<>();

    public RepeatTracker(long maxStreakMillis, long resetAfterMillis) {
        this.maxStreakMillis = maxStreakMillis;
        this.resetAfterMillis = resetAfterMillis;
    }

    private long lastStreakStart;

    /** @return true if this action should count as activity */
    public boolean accept(String key, long now) {
        if (streaks.size() > 64) prune(now);

        long[] streak = streaks.get(key);
        if (streak == null || now - streak[1] > resetAfterMillis) {
            streaks.put(key, new long[]{now, now});
            lastStreakStart = now;
            return true;
        }
        streak[1] = now;
        lastStreakStart = streak[0];
        return now - streak[0] <= maxStreakMillis;
    }

    /** When the streak of the last accepted action began. Credit after this was for repetition. */
    public long lastStreakStart() {
        return lastStreakStart;
    }

    private void prune(long now) {
        Iterator<long[]> it = streaks.values().iterator();
        while (it.hasNext()) {
            if (now - it.next()[1] > resetAfterMillis) it.remove();
        }
    }
}
