package dev.antiafk;

import dev.antiafk.core.ActivityLedger;
import dev.antiafk.core.ClickTracker;
import dev.antiafk.core.LookTracker;
import dev.antiafk.core.MovementTracker;
import dev.antiafk.core.RepeatTracker;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything AntiAFK tracks about one online player. Updated on the main thread;
 * placeholders may read {@code afk} and the last activity from other threads.
 */
public final class PlayerSession {

    public final UUID uuid;

    // Activity
    final ActivityLedger ledger = new ActivityLedger();
    String lastActivityKind = "join";
    /** Latest credited activity per kind (look, move, break, ...), so one kind's credit can be taken back. */
    final Map<String, Long> channels = new HashMap<>();

    // State
    volatile boolean afk;
    long afkSince;
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
    final Map<String, String> lastIgnored = new HashMap<>();
    /** Rate limit for debug lines: kind -> last time sent. */
    final Map<String, Long> lastDebug = new HashMap<>();

    PlayerSession(UUID uuid, long now, Settings settings) {
        this.uuid = uuid;
        credit("join", now);
        rebuildDetectors(settings);
    }

    void rebuildDetectors(Settings s) {
        look = new LookTracker(s.lookMinDegrees, s.lookHistory);
        movement = new MovementTracker(s.moveRadius, s.moveLoopMemory);
        repeats = new RepeatTracker(s.repeatMaxStreakMillis, s.repeatResetAfterMillis);
        attackClicks = new ClickTracker(s.clickSamples, s.clickMaxDeviationMillis, s.clickMaxAverageMillis);
        useClicks = new ClickTracker(s.clickSamples, s.clickMaxDeviationMillis, s.clickMaxAverageMillis);
    }

    void credit(String kind, long time) {
        ledger.credit(kind, time);
    }

    /** Takes back credit {@code kind} earned after {@code since}. @return true if the last activity moved back */
    boolean rewind(String kind, long since) {
        return ledger.rewind(kind, since);
    }

    public boolean isAfk() {
        return afk;
    }

    public String lastActivityKind() {
        return lastActivityKind;
    }

    public long lastActivity() {
        return ledger.lastActivity();
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

    public Map<String, String> lastIgnored() {
        return Map.copyOf(lastIgnored);
    }

    public boolean isAttackClickingRobotic() {
        return attackClicks.isRobotic();
    }

    public boolean isUseClickingRobotic() {
        return useClicks.isRobotic();
    }

    public boolean isFrozen(long now) {
        return frozenUntil > now;
    }

    public long idleMillis(long now) {
        return Math.max(0, now - ledger.lastActivity());
    }

    public long afkMillis(long now) {
        return afk ? Math.max(0, now - afkSince) : 0;
    }
}
