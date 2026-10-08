package dev.antiafk;

import dev.antiafk.core.ActivityKind;
import dev.antiafk.core.ActivityLedger;
import dev.antiafk.core.ClickTracker;
import dev.antiafk.core.LookTracker;
import dev.antiafk.core.MovementTracker;
import dev.antiafk.core.RepeatTracker;
import org.bukkit.Location;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything DirekAntiAFK tracks about one online player. Updated on the main thread;
 * placeholders may read {@link #isAfk()} and {@link #idleMillis(long)} from other threads.
 */
public final class PlayerSession {

    private static final int KINDS = ActivityKind.values().length;

    public final UUID uuid;

    // Activity
    final ActivityLedger ledger;
    ActivityKind lastActivityKind = ActivityKind.JOIN;

    // State
    volatile boolean afk;
    /** Idle time at which this AFK period's check (or actions) happens, picked from action-time. */
    long actionAtMillis;
    /** When the "are you still there" check was shown, or 0 if it isn't showing. */
    long checkStartedAt;
    /** The actions already ran for this AFK period; don't run them again until the player is back. */
    boolean actioned;
    /** Where to send the player back to once they're active again, or null. */
    Location returnLocation;
    /** Until when the player is held in place so a warp warmup isn't cancelled (0 = not frozen). */
    long frozenUntil;

    // Detectors (rebuilt on reload)
    LookTracker look;
    MovementTracker movement;
    RepeatTracker repeats;
    ClickTracker attackClicks;
    ClickTracker useClicks;

    /** Latest reason each kind of activity was ignored, for /antiafk check. */
    final Map<ActivityKind, String> lastIgnored = new EnumMap<>(ActivityKind.class);
    /** Debug rate limit: last time a line was sent per kind, [counted, ignored]. */
    final long[] lastDebug = new long[KINDS * 2];

    PlayerSession(UUID uuid, long now, Settings settings) {
        this.uuid = uuid;
        this.ledger = new ActivityLedger(settings.clickOnlyLimitMillis);
        ledger.credit(ActivityKind.JOIN, now);
        rebuildDetectors(settings);
    }

    void rebuildDetectors(Settings s) {
        ledger.setClickOnlyLimit(s.clickOnlyLimitMillis);
        look = new LookTracker(s.lookMinDegrees, s.lookCellDegrees, s.lookHistory);
        movement = new MovementTracker(s.moveRadius, s.moveLoopMemory);
        repeats = new RepeatTracker(s.repeatMaxStreakMillis, s.repeatResetAfterMillis);
        attackClicks = new ClickTracker(s.clickSamples, s.clickMaxDeviationMillis, s.clickMaxAverageMillis, s.clickPauseMillis);
        useClicks = new ClickTracker(s.clickSamples, s.clickMaxDeviationMillis, s.clickMaxAverageMillis, s.clickPauseMillis);
    }

    public boolean isAfk() {
        return afk;
    }

    public ActivityKind lastActivityKind() {
        return lastActivityKind;
    }

    public long lastActivity() {
        return ledger.lastActivity();
    }

    public long lastRealInput() {
        return ledger.lastRealInput();
    }

    public long idleMillis(long now) {
        return Math.max(0, now - ledger.lastActivity());
    }

    public long actionAtMillis() {
        return actionAtMillis;
    }

    public boolean isCheckShowing() {
        return checkStartedAt != 0;
    }

    public boolean actionsRan() {
        return actioned;
    }

    public boolean hasReturnLocation() {
        return returnLocation != null;
    }

    public boolean isFrozen(long now) {
        return frozenUntil > now;
    }

    public Map<ActivityKind, String> lastIgnored() {
        return new EnumMap<>(lastIgnored);
    }

    public boolean isAttackClickingRobotic() {
        return attackClicks.isCaught();
    }

    public boolean isUseClickingRobotic() {
        return useClicks.isCaught();
    }
}
