package dev.antiafk.core;

/**
 * Spots auto-clickers and held-down buttons by how evenly spaced the clicks are.
 * <p>
 * People click at uneven intervals. Macros, auto-clickers and holding a button down produce clicks
 * at almost exactly the same interval every time. Once the last {@code samples} intervals are that
 * regular, clicks stop counting as activity until the rhythm breaks.
 */
public final class ClickTracker {

    private final int samples;
    private final double maxDeviationMillis;
    private final double maxAverageMillis;
    private final long[] times;
    private int count;
    private int next;

    /**
     * @param samples            how many recent clicks to look at
     * @param maxDeviationMillis intervals varying less than this (standard deviation) are robotic
     * @param maxAverageMillis   only clicking at least this often is checked
     */
    public ClickTracker(int samples, double maxDeviationMillis, double maxAverageMillis) {
        this.samples = Math.max(3, samples);
        this.maxDeviationMillis = maxDeviationMillis;
        this.maxAverageMillis = maxAverageMillis;
        this.times = new long[this.samples + 1];
    }

    /** Records a click. @return true if it should count, false if the clicking looks automated */
    public boolean accept(long now) {
        times[next] = now;
        next = (next + 1) % times.length;
        if (count < times.length) count++;
        return !isRobotic();
    }

    public boolean isRobotic() {
        if (count < times.length) return false;

        double[] intervals = new double[samples];
        int start = next; // oldest entry
        for (int i = 0; i < samples; i++) {
            long a = times[(start + i) % times.length];
            long b = times[(start + i + 1) % times.length];
            intervals[i] = b - a;
        }

        double sum = 0;
        for (double v : intervals) sum += v;
        double mean = sum / samples;
        if (mean > maxAverageMillis) return false;

        double squares = 0;
        for (double v : intervals) squares += (v - mean) * (v - mean);
        double deviation = Math.sqrt(squares / samples);
        return deviation <= maxDeviationMillis;
    }
}
