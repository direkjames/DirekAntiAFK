package dev.antiafk.core;

/**
 * Remembers when each kind of activity last earned credit. The player's last activity is the newest credit.
 * <ul>
 *     <li>Credit from one kind can be taken back once it turns out to be automated, without touching the rest.</li>
 *     <li>Click-type activity only earns credit within {@code clickOnlyLimitMillis} of the last real input
 *     (looking, moving, chatting). After that, all click-type credit since that real input is taken back.
 *     This stops every click macro, at any speed, from keeping a player "active" on its own.</li>
 * </ul>
 * No allocation per event: credits live in a small array indexed by kind.
 */
public final class ActivityLedger {

    private static final ActivityKind[] KINDS = ActivityKind.values();

    private final long[] credits = new long[KINDS.length];
    private long clickOnlyLimitMillis;
    private volatile long lastActivity;
    /** Latest credit from a kind that isn't click-only. */
    private long lastRealInput;

    public ActivityLedger(long clickOnlyLimitMillis) {
        this.clickOnlyLimitMillis = clickOnlyLimitMillis;
    }

    public void setClickOnlyLimit(long millis) {
        this.clickOnlyLimitMillis = millis;
    }

    /** @return true if the activity earned credit, false if it was click-only past the limit */
    public boolean credit(ActivityKind kind, long time) {
        if (kind.clickOnly && time - lastRealInput > clickOnlyLimitMillis) {
            for (ActivityKind k : KINDS) {
                if (k.clickOnly && credits[k.ordinal()] > lastRealInput) credits[k.ordinal()] = lastRealInput;
            }
            recompute();
            return false;
        }
        credits[kind.ordinal()] = Math.max(credits[kind.ordinal()], time);
        if (!kind.clickOnly) lastRealInput = Math.max(lastRealInput, time);
        if (time > lastActivity) lastActivity = time;
        return true;
    }

    /**
     * Takes back the credit {@code kind} earned after {@code since}.
     * @return true if the last activity moved back
     */
    public boolean rewind(ActivityKind kind, long since) {
        int i = kind.ordinal();
        if (credits[i] <= since) return false;
        credits[i] = since;
        if (!kind.clickOnly) {
            long newest = 0;
            for (ActivityKind k : KINDS) if (!k.clickOnly) newest = Math.max(newest, credits[k.ordinal()]);
            lastRealInput = newest;
        }
        long before = lastActivity;
        recompute();
        return lastActivity < before;
    }

    /** Sets the clock as if the last activity was at {@code time} (used to carry idle time across a relog). */
    public void restore(long time) {
        java.util.Arrays.fill(credits, 0);
        credits[ActivityKind.JOIN.ordinal()] = time;
        lastRealInput = time;
        lastActivity = time;
    }

    private void recompute() {
        long newest = 0;
        for (long t : credits) newest = Math.max(newest, t);
        lastActivity = newest;
    }

    public long lastActivity() {
        return lastActivity;
    }

    public long lastRealInput() {
        return lastRealInput;
    }
}
