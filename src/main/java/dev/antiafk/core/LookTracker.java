package dev.antiafk.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides whether a head rotation is real mouse movement.
 * <p>
 * A rotation counts when it turns at least {@code minDegrees} AND lands on a direction that wasn't
 * used recently. Human mouse movement almost never repeats the exact same angles, while rotation
 * macros (looking back and forth, spinning in circles) revisit the same angles over and over.
 */
public final class LookTracker {

    /** Angles are compared at this precision, in degrees. */
    private static final float PRECISION = 0.5f;

    private final float minDegrees;
    private final int historySize;
    private final Deque<Long> history = new ArrayDeque<>();
    private final Set<Long> seen = new HashSet<>();

    private boolean hasLast;
    private float lastYaw;
    private float lastPitch;

    public LookTracker(float minDegrees, int historySize) {
        this.minDegrees = minDegrees;
        this.historySize = Math.max(1, historySize);
    }

    /** @return true if this rotation should count as activity */
    public boolean accept(float yaw, float pitch) {
        if (!hasLast) {
            hasLast = true;
            lastYaw = yaw;
            lastPitch = pitch;
            remember(key(yaw, pitch));
            return false;
        }

        float turned = Math.max(Math.abs(wrap(yaw - lastYaw)), Math.abs(pitch - lastPitch));
        if (turned < minDegrees) {
            return false;
        }
        lastYaw = yaw;
        lastPitch = pitch;

        long key = key(yaw, pitch);
        if (seen.contains(key)) {
            return false; // looked here recently: typical of a rotation macro
        }
        remember(key);
        return true;
    }

    private void remember(long key) {
        history.addLast(key);
        seen.add(key);
        while (history.size() > historySize) {
            seen.remove(history.removeFirst());
        }
    }

    private static long key(float yaw, float pitch) {
        long y = Math.round(normalize(yaw) / PRECISION);
        long p = Math.round(pitch / PRECISION);
        return (y << 32) ^ (p & 0xFFFFFFFFL);
    }

    /** Yaw in [0, 360). */
    private static float normalize(float yaw) {
        float y = yaw % 360f;
        return y < 0 ? y + 360f : y;
    }

    /** Smallest signed difference between two yaw values, in (-180, 180]. */
    private static float wrap(float diff) {
        float d = diff % 360f;
        if (d > 180f) d -= 360f;
        if (d <= -180f) d += 360f;
        return d;
    }
}
