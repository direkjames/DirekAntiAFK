package dev.antiafk.core;

/**
 * Spots auto-clickers and held-down buttons by how evenly spaced the clicks are.
 * <p>
 * Auto-clickers click on a fixed schedule (e.g. every 2 game ticks). By the time those clicks reach the
 * server, network lag has shifted each one by a few dozen milliseconds, so single gaps between clicks
 * look uneven. Lag only shifts clicks, though; it doesn't add or remove time. So instead of single gaps,
 * we compare the total time taken by each group of {@code GROUP} clicks. For a machine those totals are
 * almost identical; for a person they differ a lot.
 */
public final class ClickTracker {

    /** Clicks per group. Lag moves a group's boundaries but barely changes its length. */
    static final int GROUP = 16;

    private final int samples;
    private final double toleranceMillis;
    private final double maxAverageMillis;
    private final long[] times;
    private int count;
    private int next;

    /**
     * @param samples          how many recent intervals to look at (rounded to a multiple of 8, at least 24)
     * @param toleranceMillis  groups whose lengths differ by less than this are robotic
     * @param maxAverageMillis only clicking at least this often is checked
     */
    public ClickTracker(int samples, double toleranceMillis, double maxAverageMillis) {
        int rounded = Math.max(3 * GROUP, (samples / GROUP) * GROUP);
        this.samples = rounded;
        this.toleranceMillis = toleranceMillis;
        this.maxAverageMillis = maxAverageMillis;
        this.times = new long[rounded + 1];
    }

    /** Records a click. @return true if it should count, false if the clicking looks automated */
    public boolean accept(long now) {
        times[next] = now;
        next = (next + 1) % times.length;
        if (count < times.length) count++;
        return !isRobotic();
    }

    /** Time of the oldest click being looked at; where an automated run started at the latest. */
    public long windowStart() {
        return count < times.length ? times[0] : times[next];
    }

    public boolean isRobotic() {
        if (count < times.length) return false;

        long first = times[next];
        long last = times[(next + times.length - 1) % times.length];
        double mean = (last - first) / (double) samples;
        if (mean > maxAverageMillis) return false;

        int groups = samples / GROUP;
        long shortest = Long.MAX_VALUE;
        long longest = Long.MIN_VALUE;
        for (int g = 0; g < groups; g++) {
            long start = times[(next + g * GROUP) % times.length];
            long end = times[(next + (g + 1) * GROUP) % times.length];
            long length = end - start;
            shortest = Math.min(shortest, length);
            longest = Math.max(longest, length);
        }
        // Allow a little relative slack too, for slow clickers where lag adds up.
        double allowed = Math.max(toleranceMillis, 0.03 * mean * GROUP);
        return longest - shortest <= allowed;
    }
}
