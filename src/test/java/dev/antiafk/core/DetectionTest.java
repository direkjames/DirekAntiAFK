package dev.antiafk.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionTest {

    // ------------------------------------------------------------------ look

    @Test
    void lookCountsRealMouseMovement() {
        LookTracker look = new LookTracker(1f, 100);
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
        LookTracker look = new LookTracker(1f, 100);
        look.accept(10, 10);
        assertFalse(look.accept(10.3f, 10.2f));
    }

    @Test
    void lookIgnoresBackAndForthMacro() {
        LookTracker look = new LookTracker(1f, 100);
        look.accept(0, 0);
        assertTrue(look.accept(90, 0));   // first time looking there
        assertFalse(look.accept(0, 0));   // back to start: seen
        assertFalse(look.accept(90, 0));  // seen
        assertFalse(look.accept(0, 0));
    }

    @Test
    void lookIgnoresSpinMacroAfterFirstLap() {
        LookTracker look = new LookTracker(1f, 200);
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
    void lookHandlesYawWrapAround() {
        LookTracker look = new LookTracker(1f, 100);
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

    @Test
    void autoClickerIsDetected() {
        ClickTracker clicks = new ClickTracker(20, 15, 1000);
        boolean last = true;
        for (int i = 0; i < 25; i++) last = clicks.accept(i * 100L);
        assertFalse(last);
        assertTrue(clicks.isRobotic());
    }

    @Test
    void autoClickerWithTickJitterIsDetected() {
        // Server ticks round clicks to 50ms; a 100ms clicker sometimes lands on 50/150
        ClickTracker clicks = new ClickTracker(20, 25, 1000);
        long t = 0;
        Random random = new Random(3);
        for (int i = 0; i < 25; i++) {
            t += random.nextInt(10) == 0 ? 150 : 100;
            clicks.accept(t);
        }
        assertTrue(clicks.isRobotic());
    }

    @Test
    void humanClickingIsNotFlagged() {
        ClickTracker clicks = new ClickTracker(20, 15, 1000);
        Random random = new Random(7);
        long t = 0;
        for (int i = 0; i < 100; i++) {
            t += 120 + random.nextInt(250);
            assertTrue(clicks.accept(t), "click " + i);
        }
    }

    @Test
    void slowRegularActionsAreIgnored() {
        // e.g. one click every 5 seconds is too slow to judge
        ClickTracker clicks = new ClickTracker(20, 15, 1000);
        for (int i = 0; i < 30; i++) assertTrue(clicks.accept(i * 5000L));
    }
}
