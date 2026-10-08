package dev.antiafk.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check of the bypass reported in testing: the AdvancedXRay Auto-Clicker mod running
 * with afk-time set to 1 minute. Mirrors what ActivityListener and AfkManager do with each click.
 */
class AutoClickerScenarioTest {

    private static final long AFK_TIME = 60_000;

    /** Clicking the air every 2 ticks (spam mode). Should be AFK about a minute after the last real input. */
    @Test
    void spamClickingIntoTheAirDoesNotKeepPlayerActive() {
        ActivityLedger ledger = new ActivityLedger();
        ClickTracker clicks = new ClickTracker(80, 150, 2000);
        RepeatTracker repeats = new RepeatTracker(60_000, 10_000);
        Random random = new Random(11);

        ledger.credit("look", 0); // last real input before walking away
        long becameAfkAt = -1;
        for (long sent = 1000; sent < 180_000; sent += 100) {
            long now = ((sent + 5 + random.nextInt(55) + 49) / 50) * 50;
            boolean human = clicks.accept(now);
            boolean fresh = repeats.accept("hit:air", now);
            if (!human) ledger.rewind("interact", clicks.windowStart());
            else if (!fresh) ledger.rewind("interact", repeats.lastStreakStart());
            else ledger.credit("interact", now);

            if (becameAfkAt < 0 && now - ledger.lastActivity() >= AFK_TIME) becameAfkAt = now;
        }
        assertTrue(becameAfkAt > 0, "never became AFK");
        // Clicks before the clicker was recognised (~8 s) still count, so allow a little over a minute.
        assertTrue(becameAfkAt <= 75_000, "became AFK at " + becameAfkAt + " ms");
    }

    /** Holding left-click on the oneblock (a block breaks every 0.75 s on the same spot). */
    @Test
    void holdMiningTheOneblockDoesNotKeepPlayerActive() {
        ActivityLedger ledger = new ActivityLedger();
        RepeatTracker repeats = new RepeatTracker(60_000, 10_000);

        ledger.credit("look", 0);
        long becameAfkAt = -1;
        for (long now = 1000; now < 180_000; now += 750) {
            if (repeats.accept("break:0,64,0", now)) ledger.credit("break", now);
            else ledger.rewind("break", repeats.lastStreakStart());
            if (becameAfkAt < 0 && now - ledger.lastActivity() >= AFK_TIME) becameAfkAt = now;
        }
        assertTrue(becameAfkAt > 0 && becameAfkAt <= 62_000, "became AFK at " + becameAfkAt + " ms");
    }

    /** A real player clicking at human speed while also looking around stays active. */
    @Test
    void realPlayerStaysActive() {
        ActivityLedger ledger = new ActivityLedger();
        ClickTracker clicks = new ClickTracker(80, 150, 2000);
        Random random = new Random(5);
        long now = 0;
        long longestIdle = 0;
        while (now < 600_000) {
            now += 120 + random.nextInt(300);
            if (clicks.accept(now)) ledger.credit("attack", now);
            else ledger.rewind("attack", clicks.windowStart());
            if (random.nextInt(20) == 0) ledger.credit("look", now);
            longestIdle = Math.max(longestIdle, now - ledger.lastActivity());
        }
        assertTrue(longestIdle < 30_000, "longest idle " + longestIdle + " ms");
    }
}
