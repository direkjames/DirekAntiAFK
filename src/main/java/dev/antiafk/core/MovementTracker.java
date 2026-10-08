package dev.antiafk.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Decides whether a change of position is real, player-driven movement.
 * <p>
 * Movement only counts when the player is holding a movement key AND ends up somewhere new:
 * <ul>
 *     <li>No keys pressed: water currents, bubble columns, minecarts, boats, pistons and being pushed
 *     move the player without any input, so they never count.</li>
 *     <li>Staying within {@code radius} of the last spot: jumping in place, walking into a wall, or
 *     holding a key in an AFK pool doesn't count.</li>
 *     <li>Returning to one of the last {@code loopMemory} spots: walking or drifting in a loop
 *     (water circles, rail loops, movement macros) doesn't count.</li>
 * </ul>
 */
public final class MovementTracker {

    private final double radiusSquared;
    private final int loopMemory;
    private final Deque<double[]> recentSpots = new ArrayDeque<>();
    private double[] anchor;

    public MovementTracker(double radius, int loopMemory) {
        this.radiusSquared = radius * radius;
        this.loopMemory = Math.max(0, loopMemory);
    }

    public enum Result {
        /** Real movement to a new place. */
        COUNTED,
        /** Moved without pressing a movement key (pushed, carried, riding). */
        NO_INPUT,
        /** Still within the radius of the last spot. */
        CONFINED,
        /** Moved, but back to a place visited recently. */
        LOOP
    }

    /**
     * @param keysPressed whether forward/back/left/right/jump is held right now
     */
    public Result accept(double x, double y, double z, boolean keysPressed) {
        if (anchor == null) {
            anchor = new double[]{x, y, z};
            return Result.CONFINED;
        }
        boolean farFromAnchor = distanceSquared(anchor, x, y, z) >= radiusSquared;
        if (!keysPressed) {
            // Follow the player while they're carried, so tapping a key in an AFK pool
            // later is compared with where they are now, not where they started.
            if (farFromAnchor) moveAnchor(x, y, z);
            return Result.NO_INPUT;
        }
        if (!farFromAnchor) {
            return Result.CONFINED;
        }

        boolean revisit = false;
        for (double[] previous : recentSpots) {
            if (distanceSquared(previous, x, y, z) < radiusSquared) {
                revisit = true;
                break;
            }
        }
        moveAnchor(x, y, z);
        return revisit ? Result.LOOP : Result.COUNTED;
    }

    private void moveAnchor(double x, double y, double z) {
        recentSpots.addLast(anchor);
        while (recentSpots.size() > loopMemory) recentSpots.removeFirst();
        anchor = new double[]{x, y, z};
    }

    /** Called after a teleport: start fresh from the new spot without counting it. */
    public void reset(double x, double y, double z) {
        anchor = new double[]{x, y, z};
    }

    private static double distanceSquared(double[] a, double x, double y, double z) {
        double dx = a[0] - x, dy = a[1] - y, dz = a[2] - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
