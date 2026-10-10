package com.direk.dkafk;

import com.direk.dkafk.core.ActivityKind;
import com.direk.dkafk.core.TimeParser;
import com.direk.dkafk.hook.PapiHook;
import com.direk.dkcore.afk.DkAfkChangeEvent;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs the AFK timeline for every player:
 * <pre>
 *   real activity ──► idle ≥ afk-time ──► AFK (tag on), a random action time is picked
 *                     idle ≥ action time ──► "Are you still there?" check
 *                     no answer within timeout ──► actions run (location saved)
 *   real input (look, move, chat) ──► not AFK, check closed, sent back to the saved location
 * </pre>
 * Every switch between AFK and active fires dkCore's {@link DkAfkChangeEvent}, so other dk plugins can react.
 * <p>
 * Author: direk james
 */
public final class AfkManager {

    private final DkAfk plugin;
    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    /** watched player -> staff watching their debug output */
    private final Map<UUID, Set<UUID>> debugWatchers = new HashMap<>();
    private final NamespacedKey returnKey;
    private final NamespacedKey idleKey;
    private final NamespacedKey quitKey;
    /** Where DirekAntiAFK (before the rename) stored the same data on each player. */
    private final NamespacedKey legacyReturnKey;
    private final NamespacedKey legacyIdleKey;
    private final NamespacedKey legacyQuitKey;
    private BukkitTask task;
    /** True while actions are running, so commands the actions run don't count as activity. */
    private boolean runningActions;

    public AfkManager(DkAfk plugin) {
        this.plugin = plugin;
        this.returnKey = new NamespacedKey(plugin, "return-location");
        this.idleKey = new NamespacedKey(plugin, "idle-at-quit");
        this.quitKey = new NamespacedKey(plugin, "quit-at");
        String legacy = DkAfk.LEGACY_NAME.toLowerCase(Locale.ROOT);
        this.legacyReturnKey = NamespacedKey.fromString(legacy + ":return-location");
        this.legacyIdleKey = NamespacedKey.fromString(legacy + ":idle-at-quit");
        this.legacyQuitKey = NamespacedKey.fromString(legacy + ":quit-at");
    }

    public void start() {
        Bukkit.getOnlinePlayers().forEach(this::join);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) quit(player);
    }

    public void reload() {
        Settings settings = plugin.settings();
        for (PlayerSession s : sessions.values()) {
            s.rebuildDetectors(settings);
            // The action-time range may have changed: pick again for anyone still waiting for their check.
            if (s.afk && !s.actioned && s.checkStartedAt == 0) s.actionAtMillis = pickActionTime(settings);
        }
    }

    // ------------------------------------------------------------------ sessions

    public void join(Player player) {
        Settings settings = plugin.settings();
        long now = System.currentTimeMillis();
        PlayerSession s = new PlayerSession(player.getUniqueId(), now, settings);
        PersistentDataContainer data = player.getPersistentDataContainer();
        migrateLegacyKeys(data);

        s.returnLocation = loadReturnLocation(player);
        // They were sent away for being AFK and haven't been back since: don't send them again.
        s.actioned = s.returnLocation != null;

        // Rejoining quickly doesn't reset the AFK timer (relogging or auto-reconnect to dodge it).
        Long idleAtQuit = data.get(idleKey, PersistentDataType.LONG);
        Long quitAt = data.get(quitKey, PersistentDataType.LONG);
        if (idleAtQuit != null && quitAt != null && !settings.rejoinMemory.isZero()
                && now - quitAt <= settings.rejoinMemory.toMillis()) {
            s.ledger.restore(now - idleAtQuit);
        }
        data.remove(idleKey);
        data.remove(quitKey);

        s.movement.reset(player.getX(), player.getY(), player.getZ());
        sessions.put(player.getUniqueId(), s);
    }

    public void quit(Player player) {
        PlayerSession s = sessions.remove(player.getUniqueId());
        if (s == null) return;
        if (s.checkStartedAt != 0) closeCheck(player, s);
        // Keep the return spot across a relog: they come back in the AFK area and return on activity.
        saveReturnLocation(player, s.returnLocation);
        long now = System.currentTimeMillis();
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(idleKey, PersistentDataType.LONG, s.idleMillis(now));
        data.set(quitKey, PersistentDataType.LONG, now);
        debugWatchers.remove(player.getUniqueId());
        debugWatchers.values().forEach(w -> w.remove(player.getUniqueId()));
    }

    public PlayerSession session(UUID uuid) {
        return sessions.get(uuid);
    }

    /**
     * Whether the player is AFK right now. This is what dkCore's {@code isAfk(uuid)} returns.
     * Safe to call from any thread (e.g. async chat).
     */
    public boolean isAfk(UUID uuid) {
        PlayerSession s = sessions.get(uuid);
        return s != null && s.isAfk();
    }

    public List<Player> afkPlayers() {
        List<Player> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerSession s = sessions.get(player.getUniqueId());
            if (s != null && s.afk) result.add(player);
        }
        return result;
    }

    /** @return why this player is never marked AFK right now, or null if they're checked */
    public String exemptReason(Player player) {
        Settings settings = plugin.settings();
        if (player.hasPermission("dkafk.bypass")) return "has the dkafk.bypass permission";
        if (settings.exemptOps && player.isOp()) return "is OP (exempt-ops is true)";
        GameMode mode = player.getGameMode();
        if (settings.exemptGameModes.contains(mode)) return "is in " + mode.name().toLowerCase(Locale.ROOT) + " mode (exempt-gamemodes)";
        if (!settings.isCheckedWorld(player.getWorld().getName())) return "is in world '" + player.getWorld().getName() + "', which isn't checked (worlds)";
        return null;
    }

    // ------------------------------------------------------------------ activity

    /** A player-driven action. Resets the AFK timer, within the rules in {@link com.direk.dkafk.core.ActivityLedger}. */
    public void counted(Player player, ActivityKind kind, String detail) {
        if (runningActions) return;
        PlayerSession s = sessions.get(player.getUniqueId());
        if (s == null) return;
        long now = System.currentTimeMillis();

        if (kind.clickOnly && s.afk) {
            ignored(player, s, kind, "clicking alone doesn't end AFK; move or look around", now);
            return;
        }
        if (!s.ledger.credit(kind, now)) {
            ignored(player, s, kind, "only clicking, no looking around or moving for over "
                    + TimeParser.format(Duration.ofMillis(plugin.settings().clickOnlyLimitMillis)), now);
            return;
        }
        s.lastActivityKind = kind;
        debug(player, s, kind, true, detail, now);

        if (s.checkStartedAt != 0) closeCheck(player, s);
        if (s.afk) setAfk(player, s, false);
        s.actioned = false;

        if (s.returnLocation != null) {
            Location target = s.returnLocation;
            s.returnLocation = null;
            saveReturnLocation(player, null);
            if (plugin.settings().returnEnabled && target.isWorldLoaded()) {
                plugin.messenger().sendRaw(player, plugin.settings().returnMessage, vars(player, s, now), player);
                player.leaveVehicle();
                player.teleportAsync(target);
            }
        }
    }

    /**
     * An action turned out to be automated (repeated past max-streak, or machine-timed clicks).
     * Take back the credit it earned since {@code since}, so it never kept the player "active".
     */
    public void rewind(Player player, ActivityKind kind, long since, String reason) {
        PlayerSession s = sessions.get(player.getUniqueId());
        if (s == null) return;
        long now = System.currentTimeMillis();
        ignored(player, s, kind, reason, now);
        if (s.ledger.rewind(kind, since)) {
            debug(player, s, kind, false, "took back credit from the last "
                    + TimeParser.format(Duration.ofMillis(now - since)), now);
        }
    }

    /** Something happened but doesn't count (carried by water, repeated action, auto-clicker...). */
    public void ignored(Player player, ActivityKind kind, String reason) {
        PlayerSession s = sessions.get(player.getUniqueId());
        if (s != null) ignored(player, s, kind, reason, System.currentTimeMillis());
    }

    private void ignored(Player player, PlayerSession s, ActivityKind kind, String reason, long now) {
        s.lastIgnored.put(kind, reason);
        debug(player, s, kind, false, reason, now);
    }

    // ------------------------------------------------------------------ timeline

    private void tick() {
        Settings settings = plugin.settings();
        long now = System.currentTimeMillis();
        long afkMillis = settings.afkTime.toMillis();
        long timeoutMillis = settings.checkTimeout.toMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerSession s = sessions.get(player.getUniqueId());
            if (s == null) {
                join(player);
                continue;
            }

            if (exemptReason(player) != null) {
                // Hold the timer still. This doesn't trigger a return; only real activity does.
                s.ledger.credit(ActivityKind.EXEMPT, now);
                if (s.checkStartedAt != 0) closeCheck(player, s);
                // A /afk they chose themselves stays; only the automatic kind is cleared.
                if (s.afk && !s.manualAfk) setAfk(player, s, false);
                continue;
            }

            long idle = s.idleMillis(now);
            if (!s.afk && idle >= afkMillis) {
                setAfk(player, s, true);
            }

            if (s.checkStartedAt != 0) {
                if (now - s.checkStartedAt >= timeoutMillis) {
                    closeCheck(player, s);
                    runActions(player, s, now);
                }
            } else if (s.afk && !s.actioned && idle >= s.actionAtMillis) {
                if (settings.checkEnabled) {
                    startCheck(player, s, now);
                } else {
                    runActions(player, s, now);
                }
            }
        }
    }

    private void setAfk(Player player, PlayerSession s, boolean afk) {
        setAfk(player, s, afk, afk ? "now-afk" : "no-longer-afk");
    }

    private void setAfk(Player player, PlayerSession s, boolean afk, String messageKey) {
        s.afk = afk;
        if (!afk) s.manualAfk = false;
        long now = System.currentTimeMillis();
        // Each AFK period gets its own random check time, so players can't learn when it comes.
        if (afk) s.actionAtMillis = pickActionTime(plugin.settings());
        Map<String, String> vars = vars(player, s, now);
        Messenger messenger = plugin.messenger();
        messenger.send(player, messageKey, vars, player);
        messenger.broadcast(afk ? "broadcast-afk" : "broadcast-back", vars, player);
        Bukkit.getPluginManager().callEvent(new DkAfkChangeEvent(player, afk));
    }

    /**
     * /afk: go AFK now, or come back if they went AFK with /afk and have been active recently.
     * <p>
     * Coming back this way never resets the AFK timer. If they've really been idle past afk-time,
     * they stay AFK and have to move or look around, so spamming /afk can't dodge detection.
     */
    public void toggleManual(Player player) {
        PlayerSession s = sessions.get(player.getUniqueId());
        if (s == null) return;
        Settings settings = plugin.settings();
        Messenger messenger = plugin.messenger();
        long now = System.currentTimeMillis();
        Map<String, String> vars = vars(player, s, now);

        long cooldown = settings.afkCommandCooldown.toMillis();
        long left = s.lastAfkCommand + cooldown - now;
        if (left > 0) {
            vars.put("time", TimeParser.format(Duration.ofMillis(Math.max(1000, left))));
            messenger.send(player, "afk-command-cooldown", vars, player);
            return;
        }
        s.lastAfkCommand = now;

        if (!s.afk) {
            s.manualAfk = true;
            setAfk(player, s, true, "afk-command-on");
            return;
        }
        if (s.manualAfk && s.idleMillis(now) < settings.afkTime.toMillis()) {
            if (s.checkStartedAt != 0) closeCheck(player, s);
            setAfk(player, s, false);
            return;
        }
        // AFK from being idle (or idle long enough since /afk): only real activity brings them back.
        s.manualAfk = false;
        messenger.send(player, "afk-command-idle", vars, player);
    }

    private static long pickActionTime(Settings settings) {
        return settings.actionTime.pickMillis(ThreadLocalRandom.current());
    }

    // ------------------------------------------------------------------ check

    private void startCheck(Player player, PlayerSession s, long now) {
        Settings settings = plugin.settings();
        s.checkStartedAt = now;
        Map<String, String> vars = vars(player, s, now);
        vars.put("timeout", TimeParser.format(settings.checkTimeout));

        // Let the button work a little past the timeout, in case of lag.
        ClickCallback.Options options = ClickCallback.Options.builder()
                .uses(1)
                .lifetime(settings.checkTimeout.plusSeconds(10))
                .build();
        UUID uuid = player.getUniqueId();

        if (settings.checkType == Settings.CheckType.DIALOG) {
            try {
                player.showDialog(buildDialog(player, vars, options, uuid));
                return;
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Couldn't show the AFK dialog to " + player.getName()
                        + " (" + e.getMessage() + "), using the chat check instead.");
            }
        }

        Messenger messenger = plugin.messenger();
        Component button = messenger.render(settings.chatButton, vars, player)
                .hoverEvent(HoverEvent.showText(messenger.render(settings.chatButtonHover, vars, player)))
                .clickEvent(ClickEvent.callback(audience -> answered(uuid), options));
        player.sendMessage(messenger.render(settings.chatMessage, vars, player).appendSpace().append(button));
    }

    private Dialog buildDialog(Player player, Map<String, String> vars, ClickCallback.Options options, UUID uuid) {
        Settings settings = plugin.settings();
        Messenger messenger = plugin.messenger();

        Component tooltip = settings.dialogButtonTooltip.isEmpty() ? null : messenger.render(settings.dialogButtonTooltip, vars, player);
        ActionButton button = ActionButton.create(
                messenger.render(settings.dialogButton, vars, player), tooltip, 200,
                DialogAction.customClick((response, audience) -> answered(uuid), options));

        DialogBase base = DialogBase.builder(messenger.render(settings.dialogTitle, vars, player))
                .canCloseWithEscape(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(settings.dialogBody.isEmpty() ? List.of()
                        : List.of(DialogBody.plainMessage(messenger.render(settings.dialogBody, vars, player))))
                .build();

        return Dialog.create(builder -> builder.empty().base(base).type(DialogType.notice(button)));
    }

    /** The player pressed "I'm here". Callbacks may arrive off the main thread. */
    private void answered(UUID uuid) {
        Runnable handle = () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) return;
            PlayerSession s = sessions.get(uuid);
            if (s != null && s.checkStartedAt != 0) {
                plugin.messenger().sendRaw(player, plugin.settings().checkPassedMessage, vars(player, s, System.currentTimeMillis()), player);
            }
            // Clicking proves they're there, even if the timeout just passed.
            counted(player, ActivityKind.CHECK, "answered the AFK check");
        };
        if (Bukkit.isPrimaryThread()) handle.run();
        else Bukkit.getScheduler().runTask(plugin, handle);
    }

    private void closeCheck(Player player, PlayerSession s) {
        s.checkStartedAt = 0;
        if (plugin.settings().checkType == Settings.CheckType.DIALOG) {
            player.closeDialog();
        }
    }

    // ------------------------------------------------------------------ actions

    private void runActions(Player player, PlayerSession s, long now) {
        Settings settings = plugin.settings();
        s.actioned = true;
        if (settings.returnEnabled && s.returnLocation == null) {
            s.returnLocation = player.getLocation();
            // Saved right away, so a crash or restart doesn't lose it.
            saveReturnLocation(player, s.returnLocation);
        }
        plugin.getLogger().info(player.getName() + " was AFK for " + TimeParser.format(Duration.ofMillis(s.idleMillis(now)))
                + (settings.checkEnabled ? " and didn't answer the check" : "") + ". Running AFK actions.");

        // Take them off vehicles and hold them still, so water, bubble columns, minecarts or pistons
        // can't cancel a warp that has a "don't move" warmup. The next teleport releases them.
        if (!settings.freezeOnActions.isZero()) {
            s.frozenUntil = now + settings.freezeOnActions.toMillis();
            player.leaveVehicle();
            player.setVelocity(new Vector());
        }

        Map<String, String> vars = vars(player, s, now);
        runningActions = true;
        try {
            for (String line : settings.actions) {
                runAction(player, line, vars);
            }
        } finally {
            runningActions = false;
        }
    }

    private void runAction(Player player, String line, Map<String, String> vars) {
        String text = line.trim();
        String type = "console";
        if (text.startsWith("[")) {
            int end = text.indexOf(']');
            if (end > 0) {
                type = text.substring(1, end).trim().toLowerCase(Locale.ROOT);
                text = text.substring(end + 1).trim();
            }
        }

        try {
            switch (type) {
                case "message" -> plugin.messenger().sendRaw(player, text, vars, player);
                case "broadcast" -> Bukkit.getServer().sendMessage(plugin.messenger().render(text, vars, player));
                case "player" -> player.performCommand(command(player, text, vars));
                case "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command(player, text, vars));
                case "teleport" -> teleport(player, text);
                default -> plugin.getLogger().warning("Unknown action type [" + type + "] in: " + line);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("AFK action '" + line + "' failed: " + e.getMessage());
        }
    }

    /** {@code [teleport] world x y z [yaw pitch]}: instant, no warmup. */
    private void teleport(Player player, String text) {
        String[] p = text.trim().split("\\s+");
        if (p.length != 4 && p.length != 6) {
            plugin.getLogger().warning("[teleport] needs: world x y z [yaw pitch]. Got: " + text);
            return;
        }
        World world = Bukkit.getWorld(p[0]);
        if (world == null) {
            plugin.getLogger().warning("[teleport] world '" + p[0] + "' doesn't exist.");
            return;
        }
        try {
            Location target = new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length == 6 ? Float.parseFloat(p[4]) : 0f, p.length == 6 ? Float.parseFloat(p[5]) : 0f);
            player.leaveVehicle();
            player.teleportAsync(target);
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("[teleport] coordinates must be numbers. Got: " + text);
        }
    }

    /** Ends the freeze, e.g. once the warp happened. */
    public void unfreeze(PlayerSession s) {
        s.frozenUntil = 0;
    }

    private String command(Player player, String text, Map<String, String> vars) {
        String command = text.startsWith("/") ? text.substring(1) : text;
        for (Map.Entry<String, String> var : vars.entrySet()) {
            command = command.replace("<" + var.getKey() + ">", var.getValue());
        }
        command = command.replace("%player%", player.getName());
        if (plugin.hasPlaceholderApi()) {
            command = PapiHook.apply(player, command);
        }
        return command;
    }

    // ------------------------------------------------------------------ debug

    public boolean toggleDebug(UUID watcher, UUID target) {
        Set<UUID> watchers = debugWatchers.computeIfAbsent(target, k -> new HashSet<>());
        if (watchers.remove(watcher)) {
            if (watchers.isEmpty()) debugWatchers.remove(target);
            return false;
        }
        watchers.add(watcher);
        return true;
    }

    private void debug(Player player, PlayerSession s, ActivityKind kind, boolean counted, String detail, long now) {
        if (debugWatchers.isEmpty()) return;
        Set<UUID> watchers = debugWatchers.get(player.getUniqueId());
        if (watchers == null || watchers.isEmpty()) return;

        // At most one line per kind and result per second; movement fires many times a second.
        int slot = kind.ordinal() * 2 + (counted ? 0 : 1);
        if (now - s.lastDebug[slot] < 1000) return;
        s.lastDebug[slot] = now;

        String line = "<dark_gray>[debug]</dark_gray> <white><player></white> <gray><kind>:</gray> "
                + (counted ? "<green>counted</green>" : "<red>ignored</red>") + " <dark_gray>(<detail>)";
        Component message = plugin.messenger().render(line,
                Map.of("player", player.getName(), "kind", kind.label, "detail", detail), null);
        for (UUID uuid : watchers) {
            Player watcher = Bukkit.getPlayer(uuid);
            if (watcher != null) watcher.sendMessage(message);
        }
    }

    // ------------------------------------------------------------------ helpers

    Map<String, String> vars(Player player, PlayerSession s, long now) {
        Map<String, String> vars = new HashMap<>();
        vars.put("player", player.getName());
        vars.put("uuid", player.getUniqueId().toString());
        vars.put("world", player.getWorld().getName());
        vars.put("afk_time", TimeParser.format(Duration.ofMillis(s.idleMillis(now))));
        return vars;
    }

    /** Moves data saved by DirekAntiAFK onto the dkAFK keys, so nobody loses their return spot or idle time. */
    private void migrateLegacyKeys(PersistentDataContainer data) {
        moveKey(data, legacyReturnKey, returnKey, PersistentDataType.STRING);
        moveKey(data, legacyIdleKey, idleKey, PersistentDataType.LONG);
        moveKey(data, legacyQuitKey, quitKey, PersistentDataType.LONG);
    }

    private static <T> void moveKey(PersistentDataContainer data, NamespacedKey from, NamespacedKey to,
                                    PersistentDataType<T, T> type) {
        T value = data.get(from, type);
        if (value == null) return;
        if (!data.has(to, type)) data.set(to, type, value);
        data.remove(from);
    }

    private void saveReturnLocation(Player player, Location location) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (location == null || !location.isWorldLoaded()) {
            data.remove(returnKey);
            return;
        }
        data.set(returnKey, PersistentDataType.STRING, location.getWorld().getUID() + ";" + location.getX() + ";"
                + location.getY() + ";" + location.getZ() + ";" + location.getYaw() + ";" + location.getPitch());
    }

    private Location loadReturnLocation(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        String text = data.get(returnKey, PersistentDataType.STRING);
        if (text == null) return null;
        try {
            String[] p = text.split(";");
            World world = Bukkit.getWorld(UUID.fromString(p[0]));
            if (world == null) {
                data.remove(returnKey); // that world is gone
                return null;
            }
            return new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (RuntimeException e) {
            data.remove(returnKey);
            return null;
        }
    }
}
