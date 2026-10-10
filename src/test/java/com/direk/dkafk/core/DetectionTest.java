package com.direk.dkafk.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionTest {

    // ------------------------------------------------------------------ look

    @Test
    void lookCountsRealMouseMovement() {
        LookTracker look = new LookTracker(1f, 2f, 200);
        look.accept(0, 0);
        int counted = 0;
        Random random = new Random(1);
        float yaw = 0, pitch = 0;
        for (int i = 0; i < 50; i++) {
            yaw += 3 + random.nextFloat() * 20;
            pitch = Math.max(-90, Math.min(90, pitch + random.nextFloat() * 10 - 5));
            if (look.accept(yaw, pitch)) counted++;
        }
        assertTrue(counted >= 45, "most human-like turns should count, got " + counted);
    }

    @Test
    void lookIgnoresTinyChanges() {
        LookTracker look = new LookTracker(1f, 2f, 200);
        look.accept(10, 10);
        assertFalse(look.accept(10.3f, 10.2f));
    }

    @Test
    void lookIgnoresBackAndForthMacro() {
        LookTracker look = new LookTracker(1f, 2f, 200);
        look.accept(0, 0);
        assertTrue(look.accept(90, 0));   // first time looking there
        assertFalse(look.accept(0, 0));   // back to start: seen
        assertFalse(look.accept(90, 0));  // seen
        assertFalse(look.accept(0, 0));
    }

    @Test
    void lookIgnoresSpinMacroAfterFirstLap() {
        LookTracker look = new LookTracker(1f, 2f, 200);
        look.accept(0, 0);
        int firstLap = 0, laterLaps = 0;
        for (int lap = 0; lap < 3; lap++) {
            for (int step = 1; step <= 72; step++) {
                boolean counted = look.accept(lap * 360 + step * 5, 0);
                if (lap == 0 && counted) firstLap++;
                if (lap > 0 && counted) laterLaps++;
            }
        }
        assertTrue(firstLap > 60);
        assertEquals(0, laterLaps);
    }

    @Test
    void lookIgnoresRandomWiggleMacro() {
        // Camera wiggled at random within a 20 x 20 degree area
        LookTracker look = new LookTracker(1f, 2f, 200);
        Random random = new Random(9);
        look.accept(0, 0);
        int countedLater = 0;
        for (int i = 0; i < 1000; i++) {
            boolean counted = look.accept(random.nextFloat() * 20 - 10, random.nextFloat() * 20 - 10);
            if (i >= 400 && counted) countedLater++;
        }
        assertTrue(countedLater <= 10, "wiggle counted " + countedLater + " times");
    }

    @Test
    void lookHandlesYawWrapAround() {
        LookTracker look = new LookTracker(1f, 2f, 200);
        look.accept(179.8f, 0);
        assertFalse(look.accept(-179.9f, 0)); // only 0.3 degrees apart
    }

    // ------------------------------------------------------------------ movement

    @Test
    void walkingSomewhereNewCounts() {
        MovementTracker move = new MovementTracker(3, 8);
        move.accept(0, 64, 0, true);
        assertEquals(MovementTracker.Result.CONFINED, move.accept(2, 64, 0, true));
        assertEquals(MovementTracker.Result.COUNTED, move.accept(4, 64, 0, true));
        assertEquals(MovementTracker.Result.COUNTED, move.accept(8, 64, 0, true));
    }

    @Test
    void beingCarriedNeverCounts() {
        // Water currents, bubble columns, minecarts, boats, pistons, being pushed
        MovementTracker move = new MovementTracker(3, 8);
        move.accept(0, 64, 0, false);
        for (int i = 1; i <= 50; i++) {
            assertEquals(MovementTracker.Result.NO_INPUT, move.accept(i * 2, 64 + i, 0, false));
        }
    }

    @Test
    void jumpingOrWalkingIntoAWallIsConfined() {
        MovementTracker move = new MovementTracker(3, 8);
        move.accept(0, 64, 0, true);
        for (int i = 0; i < 100; i++) {
            double y = 64 + (i % 2 == 0 ? 1.25 : 0);
            assertEquals(MovementTracker.Result.CONFINED, move.accept(0.2, y, 0.1, true));
        }
    }

    @Test
    void walkingInALoopStopsCounting() {
        MovementTracker move = new MovementTracker(3, 8);
        double[][] loop = {{0, 0}, {5, 0}, {5, 5}, {0, 5}};
        move.accept(0, 64, 0, true);
        int countedLater = 0;
        for (int lap = 0; lap < 5; lap++) {
            for (double[] p : loop) {
                if (move.accept(p[0], 64, p[1], true) == MovementTracker.Result.COUNTED && lap > 0) countedLater++;
            }
        }
        assertEquals(0, countedLater);
    }

    @Test
    void tappingAKeyInAnAfkPoolDoesNotCount() {
        MovementTracker move = new MovementTracker(3, 8);
        move.accept(0, 64, 0, false);
        // carried 40 blocks by water
        for (int i = 1; i <= 20; i++) move.accept(i * 2, 64, 0, false);
        // tap W: moves a little from where the water left them
        assertEquals(MovementTracker.Result.CONFINED, move.accept(40.5, 64, 0, true));
    }

    @Test
    void teleportResetsWithoutCounting() {
        MovementTracker move = new MovementTracker(3, 8);
        move.accept(0, 64, 0, true);
        move.reset(1000, 64, 1000);
        assertEquals(MovementTracker.Result.CONFINED, move.accept(1001, 64, 1000, true));
    }

    // ------------------------------------------------------------------ repeat

    @Test
    void repeatingTheSameActionStopsCounting() {
        RepeatTracker repeat = new RepeatTracker(60_000, 10_000);
        long t = 0;
        for (; t <= 60_000; t += 500) assertTrue(repeat.accept("break:0,64,0", t));
        assertFalse(repeat.accept("break:0,64,0", t));
        assertFalse(repeat.accept("break:0,64,0", t + 5_000)); // still within reset-after: still a streak
    }

    @Test
    void differentActionsKeepCounting() {
        RepeatTracker repeat = new RepeatTracker(60_000, 10_000);
        for (long t = 0; t < 300_000; t += 1000) {
            assertTrue(repeat.accept("break:" + (t / 1000), t));
        }
    }

    @Test
    void streakResetsAfterABreak() {
        RepeatTracker repeat = new RepeatTracker(60_000, 10_000);
        for (long t = 0; t <= 70_000; t += 1000) repeat.accept("chat:hi", t);
        assertFalse(repeat.accept("chat:hi", 71_000));
        assertTrue(repeat.accept("chat:hi", 71_000 + 10_001)); // waited longer than reset-after
    }

    // ------------------------------------------------------------------ clicks

    /** What the server sees: sent on schedule, delayed by network lag, handled at the next 50 ms tick. */
    private static long arrival(long sentMillis, Random random, int maxLagMillis) {
        long arrived = sentMillis + 5 + random.nextInt(maxLagMillis);
        return ((arrived + 49) / 50) * 50;
    }

    @Test
    void autoClickerIsDetectedDespiteLag() {
        // AdvancedXRay Auto-Clicker in spam mode: a click every 2 ticks
        ClickTracker clicks = new ClickTracker(80, 150, 2000, 5000);
        Random random = new Random(1);
        int countedAfterWarmup = 0;
        for (int i = 0; i < 600; i++) {
            boolean counted = clicks.accept(arrival(i * 100L, random, 55));
            if (i > 80 && counted) countedAfterWarmup++;
        }
        assertEquals(0, countedAfterWarmup);
    }

    @Test
    void slowAutoClickerIsDetected() {
        // one click per second, e.g. respecting the sword cooldown or a slow right-click macro
        ClickTracker clicks = new ClickTracker(80, 150, 2000, 5000);
        Random random = new Random(2);
        boolean last = true;
        for (int i = 0; i < 100; i++) last = clicks.accept(arrival(i * 1000L, random, 55));
        assertFalse(last);
    }

    @Test
    void heldRightClickIsDetected() {
        // Vanilla repeats "use" every 4 ticks while the button is held down
        ClickTracker clicks = new ClickTracker(80, 150, 2000, 5000);
        boolean last = true;
        for (int i = 0; i < 100; i++) last = clicks.accept(i * 200L);
        assertFalse(last);
    }

    @Test
    void humanClickingIsNotFlagged() {
        ClickTracker clicks = new ClickTracker(80, 150, 2000, 5000);
        Random random = new Random(7);
        long t = 0;
        int counted = 0;
        for (int i = 0; i < 2000; i++) {
            t += 120 + random.nextInt(250);
            if (clicks.accept(((t + 49) / 50) * 50)) counted++;
        }
        assertTrue(counted > 1900, "human clicks counted: " + counted + "/2000");
    }

    @Test
    void verySlowRegularActionsAreIgnored() {
        ClickTracker clicks = new ClickTracker(80, 150, 2000, 5000);
        for (int i = 0; i < 200; i++) assertTrue(clicks.accept(i * 5000L));
    }

    @Test
    void pauseEndsTheRun() {
        ClickTracker clicks = new ClickTracker(48, 150, 2000, 5000);
        for (int i = 0; i < 100; i++) clicks.accept(i * 100L);
        assertTrue(clicks.isCaught());
        assertEquals(0, clicks.runStart());
        assertTrue(clicks.accept(100 * 100L + 6000)); // paused 6 s: a new run, not judged yet
        assertEquals(100 * 100L + 6000, clicks.runStart());
    }

    @Test
    void repeatTrackerReportsWhenTheStreakStarted() {
        RepeatTracker repeat = new RepeatTracker(60_000, 10_000);
        for (long t = 5_000; t <= 70_000; t += 1000) repeat.accept("break:0,64,0", t);
        assertEquals(5_000, repeat.lastStreakStart());
        repeat.accept("break:9,64,9", 71_000);
        assertEquals(71_000, repeat.lastStreakStart());
    }
}

