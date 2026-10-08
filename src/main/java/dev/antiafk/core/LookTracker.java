package dev.antiafk.core;

/**
 * Decides whether a head rotation is real mouse movement.
 * <p>
 * The view is split into cells of {@code cellDegrees} by {@code cellDegrees}. A rotation counts when it
 * turns at least {@code minDegrees} AND lands in a cell that isn't one of the last {@code historySize}
 * cells looked at. People look all over the place; look macros (flicking back and forth, spinning,
 * wiggling the camera at random within a small area) keep landing in the same few cells.
 * <p>
 * Called on every mouse movement, so it uses a plain array and allocates nothing.
 */
public final class LookTracker {

    private final float minDegrees;
    private final float cellDegrees;
    private final long[] history;
    private int size;
    private int next;

    private boolean hasLast;
    private float lastYaw;
    private float lastPitch;

    public LookTracker(float minDegrees, float cellDegrees, int historySize) {
        this.minDegrees = minDegrees;
        this.cellDegrees = Math.max(0.1f, cellDegrees);
        this.history = new long[Math.max(1, historySize)];
    }

    /** @return true if this rotation should count as activity */
    public boolean accept(float yaw, float pitch) {
        if (!hasLast) {
            hasLast = true;
            lastYaw = yaw;
            lastPitch = pitch;
            remember(cell(yaw, pitch));
            return false;
        }

        float turned = Math.max(Math.abs(wrap(yaw - lastYaw)), Math.abs(pitch - lastPitch));
        if (turned < minDegrees) {
            return false;
        }
        lastYaw = yaw;
        lastPitch = pitch;

        long cell = cell(yaw, pitch);
        for (int i = 0; i < size; i++) {
            if (history[i] == cell) return false; // looked here recently
        }
        remember(cell);
        return true;
    }

    private void remember(long cell) {
        history[next] = cell;
        next = (next + 1) % history.length;
        if (size < history.length) size++;
    }

    private long cell(float yaw, float pitch) {
        long y = (long) Math.floor(normalize(yaw) / cellDegrees);
        long p = (long) Math.floor((pitch + 90f) / cellDegrees);
        return (y << 32) | (p & 0xFFFFFFFFL);
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
