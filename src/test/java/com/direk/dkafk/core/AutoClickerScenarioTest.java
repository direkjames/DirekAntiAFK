package com.direk.dkafk.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks of the bypasses found in testing, mirroring what ActivityListener and AfkManager
 * do with each event. afk-time is 1 minute and click-only-limit is 1 minute, as in the test setup.
 */
class AutoClickerScenarioTest {

    private static final long AFK_TIME = 60_000;
    private static final long CLICK_ONLY_LIMIT = 60_000;

    /** Feeds one click through the same steps as ActivityListener.click(). */
    private static void click(ActivityLedger ledger, ClickTracker clicks, RepeatTracker repeats, String key, long now) {
        boolean human = clicks.accept(now);
        boolean fresh = repeats.accept(key, now);
        if (!human) ledger.rewind(ActivityKind.INTERACT, clicks.runStart());
        else if (!fresh) ledger.rewind(ActivityKind.INTERACT, repeats.lastStreakStart());
        else ledger.credit(ActivityKind.INTERACT, now);
    }

    private static long becomesAfkAt(long clickEveryMillis, int lagMillis, long seed) {
        ActivityLedger ledger = new ActivityLedger(CLICK_ONLY_LIMIT);
        ClickTracker clicks = new ClickTracker(80, 150, 2000, 5000);
        RepeatTracker repeats = new RepeatTracker(60_000, 10_000);
        Random random = new Random(seed);
        ledger.credit(ActivityKind.LOOK, 0); // last real input
        for (long sent = 1000; sent < 600_000; sent += clickEveryMillis) {
            long now = ((sent + 5 + random.nextInt(lagMillis) + 49) / 50) * 50;
            click(ledger, clicks, repeats, "hit:air", now);
            if (now - ledger.lastActivity() >= AFK_TIME) return now;
        }
        return -1;
    }

    @Test
    void autoClickerAtAnySpeedBecomesAfkAboutAMinuteAfterLastRealInput() {
        // From every tick to once every 20 s (the mod's delay is a free number of ticks)
        for (long every : new long[]{50, 100, 250, 1000, 5000, 11_000, 20_000}) {
            long at = becomesAfkAt(every, 55, every);
            assertTrue(at > 0 && at <= 81_000, "clicking every " + every + " ms became AFK at " + at + " ms");
        }
    }

    @Test
    void autoClickerOnAVeryLaggyConnectionIsStillCaught() {
        long at = becomesAfkAt(100, 150, 3);
        assertTrue(at > 0 && at <= 81_000, "became AFK at " + at + " ms");
    }

    @Test
    void lagSpikesDontKeepAnAutoClickerActive() {
        // Every 20 s the server freezes for 700 ms and handles the backlog of clicks at once
        ActivityLedger ledger = new ActivityLedger(CLICK_ONLY_LIMIT);
        ClickTracker clicks = new ClickTracker(80, 150, 2000, 5000);
        RepeatTracker repeats = new RepeatTracker(60_000, 10_000);
        Random random = new Random(4);
        ledger.credit(ActivityKind.LOOK, 0);
        long becameAfkAt = -1;
        for (int i = 10; i < 6000 && becameAfkAt < 0; i++) {
            long arrived = (i % 200 < 7) ? (i - i % 200) * 100L + 700 : i * 100L + 5 + random.nextInt(55);
            long now = ((arrived + 49) / 50) * 50;
            click(ledger, clicks, repeats, "hit:air", now);
            if (now - ledger.lastActivity() >= AFK_TIME) becameAfkAt = now;
        }
        assertTrue(becameAfkAt > 0 && becameAfkAt <= 81_000, "became AFK at " + becameAfkAt + " ms");
    }

    @Test
    void holdMiningTheOneblockBecomesAfk() {
        ActivityLedger ledger = new ActivityLedger(CLICK_ONLY_LIMIT);
        RepeatTracker repeats = new RepeatTracker(60_000, 10_000);
        ledger.credit(ActivityKind.LOOK, 0);
        long becameAfkAt = -1;
        for (long now = 1000; now < 300_000 && becameAfkAt < 0; now += 750) {
            if (repeats.accept("break:0,64,0", now)) ledger.credit(ActivityKind.BREAK, now);
            else ledger.rewind(ActivityKind.BREAK, repeats.lastStreakStart());
            if (now - ledger.lastActivity() >= AFK_TIME) becameAfkAt = now;
        }
        assertTrue(becameAfkAt > 0 && becameAfkAt <= 62_000, "became AFK at " + becameAfkAt + " ms");
    }

    @Test
    void mobGrinderWithChangingTargetsBecomesAfk() {
        // Attacks different mob types at human-looking intervals: only the click-only limit can catch this
        ActivityLedger ledger = new ActivityLedger(CLICK_ONLY_LIMIT);
        Random random = new Random(8);
        ledger.credit(ActivityKind.LOOK, 0);
        long now = 0, becameAfkAt = -1;
        while (now < 600_000 && becameAfkAt < 0) {
            now += 300 + random.nextInt(900);
            ledger.credit(ActivityKind.ATTACK, now);
            if (now - ledger.lastActivity() >= AFK_TIME) becameAfkAt = now;
        }
        assertTrue(becameAfkAt > 0 && becameAfkAt <= 62_000, "became AFK at " + becameAfkAt + " ms");
    }

    @Test
    void realPlayerWhoClicksAndLooksAroundStaysActive() {
        ActivityLedger ledger = new ActivityLedger(CLICK_ONLY_LIMIT);
        ClickTracker clicks = new ClickTracker(80, 150, 2000, 5000);
        RepeatTracker repeats = new RepeatTracker(60_000, 10_000);
        Random random = new Random(5);
        long now = 0, longestIdle = 0;
        while (now < 1_800_000) {
            now += 120 + random.nextInt(300);
            click(ledger, clicks, repeats, "hit:" + random.nextInt(5), now);
            if (random.nextInt(30) == 0) ledger.credit(ActivityKind.LOOK, now);
            longestIdle = Math.max(longestIdle, now - ledger.lastActivity());
        }
        // Never idle long enough to be marked AFK
        assertTrue(longestIdle < AFK_TIME, "longest idle " + longestIdle + " ms");
    }

    @Test
    void realPlayerMiningWithOnlyOccasionalMouseMovementStaysActive() {
        // Mines the oneblock and nudges the mouse about once every 40 s
        ActivityLedger ledger = new ActivityLedger(CLICK_ONLY_LIMIT);
        RepeatTracker repeats = new RepeatTracker(60_000, 10_000);
        long longestIdle = 0;
        for (long now = 0; now < 1_800_000; now += 750) {
            if (now % 40_000 < 750) ledger.credit(ActivityKind.LOOK, now);
            if (repeats.accept("break:0,64,0", now)) ledger.credit(ActivityKind.BREAK, now);
            else ledger.rewind(ActivityKind.BREAK, repeats.lastStreakStart());
            longestIdle = Math.max(longestIdle, now - ledger.lastActivity());
        }
        assertTrue(longestIdle < 45_000, "longest idle " + longestIdle + " ms");
    }
}
