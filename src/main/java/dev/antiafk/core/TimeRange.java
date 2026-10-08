package dev.antiafk.core;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * A fixed time ({@code 10m}) or a range ({@code 6m-10m}) to pick a random time from.
 */
public record TimeRange(Duration min, Duration max) {

    public TimeRange {
        if (max.compareTo(min) < 0) throw new IllegalArgumentException("The range's end is before its start");
    }

    public static TimeRange of(Duration fixed) {
        return new TimeRange(fixed, fixed);
    }

    /** @throws IllegalArgumentException if the text isn't a valid time or range */
    public static TimeRange parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Time is empty");
        int dash = text.indexOf('-');
        if (dash < 0) return of(TimeParser.parse(text));
        Duration min = TimeParser.parse(text.substring(0, dash));
        Duration max = TimeParser.parse(text.substring(dash + 1));
        if (max.compareTo(min) < 0) {
            throw new IllegalArgumentException("In '" + text.trim() + "' the second time must be longer than the first");
        }
        return new TimeRange(min, max);
    }

    public boolean isFixed() {
        return min.equals(max);
    }

    /** @return a random time between min and max (inclusive), in milliseconds */
    public long pickMillis(RandomGenerator random) {
        long lo = min.toMillis();
        long hi = max.toMillis();
        return lo == hi ? lo : lo + random.nextLong(hi - lo + 1);
    }

    @Override
    public String toString() {
        return isFixed() ? TimeParser.format(min) : TimeParser.format(min) + " - " + TimeParser.format(max);
    }
}
