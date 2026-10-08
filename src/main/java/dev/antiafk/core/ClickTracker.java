package dev.antiafk.core;

/**
 * Spots auto-clickers and held-down buttons by how evenly spaced the clicks are.
 * <p>
 * Auto-clickers click on a fixed schedule (e.g. every 2 game ticks). By the time those clicks reach the
 * server, network lag has shifted each one by a few dozen milliseconds, so single gaps between clicks
 * look uneven. Lag only shifts clicks, though; it doesn't add or remove time. So instead of single gaps,
 * we compare the total time taken by each group of {@code GROUP} clicks. For a machine those totals are
 * almost identical; for a person they differ a lot.
 * <p>
 * A pause of {@code pauseMillis} ends a run of clicks; a new run is only judged once it has enough clicks.
 * (A lag spike can briefly make a clicker look human. That's fine: click-type activity only keeps a
 * player active for a limited time after their last real input anyway, see ActivityLedger.)
 */
public final class ClickTracker {

    /** Clicks per group. Lag moves a group's boundaries but barely changes its length. */
    static final int GROUP = 16;

    private final int samples;
    private final double toleranceMillis;
    private final double maxAverageMillis;
    private final long pauseMillis;
    private final long[] times;
    private int count;
    private int next;

    private boolean caught;
    private long runStart;
    private long lastClick;

    /**
     * @param samples          how many recent intervals to look at (rounded to a multiple of 16, at least 48)
     * @param toleranceMillis  groups whose lengths differ by less than this are robotic
     * @param maxAverageMillis only clicking at least this often is checked
     * @param pauseMillis      a pause this long ends a run of clicks
     */
    public ClickTracker(int samples, double toleranceMillis, double maxAverageMillis, long pauseMillis) {
        int rounded = Math.max(3 * GROUP, (samples / GROUP) * GROUP);
        this.samples = rounded;
        this.toleranceMillis = toleranceMillis;
        this.maxAverageMillis = maxAverageMillis;
        this.pauseMillis = pauseMillis;
        this.times = new long[rounded + 1];
    }

    /** Records a click. @return true if it should count, false if this run of clicks is automated */
    public boolean accept(long now) {
        if (count > 0 && now - lastClick > pauseMillis) {
            count = 0;
            next = 0;
        }
        if (count == 0) runStart = now;
        lastClick = now;

        times[next] = now;
        next = (next + 1) % times.length;
        if (count < times.length) count++;

        caught = looksAutomated();
        return !caught;
    }

    /** When the current run of clicks began (the last pause longer than pauseMillis). */
    public long runStart() {
        return runStart;
    }

    /** Whether the latest click looked automated. */
    public boolean isCaught() {
        return caught;
    }

    private boolean looksAutomated() {
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
        // A little relative slack too, for slow clickers where lag adds up.
        double allowed = Math.max(toleranceMillis, 0.03 * mean * GROUP);
        return longest - shortest <= allowed;
    }
}
