package com.direk.dkafk;

import com.direk.dkafk.core.TimeParser;
import com.direk.dkafk.core.TimeRange;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/** Everything read from config.yml, validated once per load/reload. */
public final class Settings {

    public enum CheckType { DIALOG, CHAT }

    public final String prefix;
    public final Duration afkTime;
    /** A fixed time or a range; each AFK period picks its own random time from it. */
    public final TimeRange actionTime;
    /** Idle time is kept if a player rejoins within this long (stops relogging/auto-reconnect resetting it). */
    public final Duration rejoinMemory;

    public final boolean checkEnabled;
    public final CheckType checkType;
    public final Duration checkTimeout;
    public final String dialogTitle;
    public final String dialogBody;
    public final String dialogButton;
    public final String dialogButtonTooltip;
    public final String chatMessage;
    public final String chatButton;
    public final String chatButtonHover;
    public final String checkPassedMessage;

    public final List<String> actions;
    public final Duration freezeOnActions;
    public final boolean returnEnabled;
    public final String returnMessage;

    public final String afkTag;
    public final boolean placeholderLegacy;

    public final boolean exemptOps;
    public final Set<GameMode> exemptGameModes;
    public final boolean worldWhitelist;
    public final Set<String> worlds;

    public final float lookMinDegrees;
    public final float lookCellDegrees;
    public final int lookHistory;
    public final double moveRadius;
    public final int moveLoopMemory;
    public final long repeatMaxStreakMillis;
    public final long repeatResetAfterMillis;
    public final int clickSamples;
    public final double clickMaxDeviationMillis;
    public final double clickMaxAverageMillis;
    public final long clickPauseMillis;
    public final long clickOnlyLimitMillis;
    public final Set<String> ignoredCommands;

    private final ConfigurationSection messages;

    public Settings(FileConfiguration c, Logger logger) {
        prefix = c.getString("prefix", "");

        Duration afk = time(c, "afk-time", "5m", logger);
        TimeRange action;
        String actionText = c.getString("action-time", "10m");
        try {
            action = TimeRange.parse(actionText);
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid action-time '" + actionText + "': " + e.getMessage() + ". Using 10m.");
            action = TimeRange.of(Duration.ofMinutes(10));
        }
        if (action.min().compareTo(afk) <= 0) {
            Duration min = afk.plusMinutes(1);
            Duration max = action.max().compareTo(min) < 0 ? min : action.max();
            logger.warning("action-time (" + action + ") must be longer than afk-time (" + TimeParser.format(afk)
                    + "). Using " + new TimeRange(min, max) + ".");
            action = new TimeRange(min, max);
        }
        afkTime = afk;
        actionTime = action;
        rejoinMemory = optionalTime(c, "rejoin-memory", "10m", logger);

        checkEnabled = c.getBoolean("check.enabled", true);
        checkType = "chat".equalsIgnoreCase(c.getString("check.type", "dialog")) ? CheckType.CHAT : CheckType.DIALOG;
        checkTimeout = time(c, "check.timeout", "30s", logger);
        dialogTitle = c.getString("check.dialog.title", "<red>Are you still there?");
        dialogBody = c.getString("check.dialog.body", "");
        dialogButton = c.getString("check.dialog.button", "<green>I'm here!");
        dialogButtonTooltip = c.getString("check.dialog.button-tooltip", "");
        chatMessage = c.getString("check.chat.message", "<red>Are you still there?");
        chatButton = c.getString("check.chat.button", "<green>[I'm here!]");
        chatButtonHover = c.getString("check.chat.button-hover", "");
        checkPassedMessage = c.getString("check.passed-message", "");

        actions = List.copyOf(c.getStringList("actions"));
        if (actions.isEmpty()) {
            logger.warning("No actions are configured, so nothing happens when a player fails the AFK check.");
        }
        freezeOnActions = optionalTime(c, "freeze-on-actions", "5s", logger);
        returnEnabled = c.getBoolean("return.enabled", true);
        returnMessage = c.getString("return.message", "");

        afkTag = c.getString("afk-tag", " <gray>[AFK]");
        placeholderLegacy = !"minimessage".equalsIgnoreCase(c.getString("placeholder-format", "legacy"));

        Set<GameMode> modes = EnumSet.noneOf(GameMode.class);
        for (String mode : c.getStringList("exempt-gamemodes")) {
            try {
                modes.add(GameMode.valueOf(mode.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                logger.warning("Unknown game mode '" + mode + "' in exempt-gamemodes.");
            }
        }
        exemptGameModes = modes;
        exemptOps = c.getBoolean("exempt-ops", true);
        worldWhitelist = "whitelist".equalsIgnoreCase(c.getString("worlds.mode", "blacklist"));
        worlds = Set.copyOf(c.getStringList("worlds.list"));

        lookMinDegrees = (float) Math.max(0.1, c.getDouble("detection.look.min-degrees", 1.0));
        lookCellDegrees = (float) Math.max(0.5, c.getDouble("detection.look.cell-degrees", 2.0));
        lookHistory = Math.max(1, c.getInt("detection.look.history", 200));
        moveRadius = Math.max(0.5, c.getDouble("detection.movement.radius", 3.0));
        moveLoopMemory = Math.max(0, c.getInt("detection.movement.loop-memory", 16));
        repeatMaxStreakMillis = time(c, "detection.repeat.max-streak", "1m", logger).toMillis();
        repeatResetAfterMillis = time(c, "detection.repeat.reset-after", "10s", logger).toMillis();
        clickSamples = Math.max(48, c.getInt("detection.clicks.samples", 80));
        clickMaxDeviationMillis = c.getDouble("detection.clicks.tolerance-ms", 150);
        clickMaxAverageMillis = c.getDouble("detection.clicks.max-average-ms", 2000);
        clickPauseMillis = time(c, "detection.clicks.pause", "5s", logger).toMillis();
        clickOnlyLimitMillis = time(c, "detection.click-only-limit", "1m", logger).toMillis();

        Set<String> ignored = new HashSet<>();
        for (String command : c.getStringList("detection.ignored-commands")) {
            ignored.add(command.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        }
        ignoredCommands = Set.copyOf(ignored);

        messages = c.getConfigurationSection("messages");
    }

    /** @return the message at {@code messages.<key>}, or "" if missing */
    public String message(String key) {
        return messages == null ? "" : messages.getString(key, "");
    }

    /** @return whether AFK checks run in this world */
    public boolean isCheckedWorld(String world) {
        return worldWhitelist == worlds.contains(world);
    }

    /** Like {@link #time}, but "0" or "" means off (zero). */
    private static Duration optionalTime(FileConfiguration c, String path, String fallback, Logger logger) {
        String text = c.getString(path, fallback).trim();
        if (text.isEmpty() || text.matches("0+[a-zA-Z]?")) return Duration.ZERO;
        return time(c, path, fallback, logger);
    }

    private static Duration time(FileConfiguration c, String path, String fallback, Logger logger) {
        String text = c.getString(path, fallback);
        try {
            return TimeParser.parse(text);
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid " + path + " '" + text + "': " + e.getMessage() + ". Using " + fallback + ".");
            return TimeParser.parse(fallback);
        }
    }
}
