package dev.antiafk;

import dev.antiafk.core.TimeParser;
import dev.antiafk.hook.PapiHook;
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
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the AFK timeline for every player:
 * <pre>
 *   real activity ──► idle ≥ afk-time ──► AFK (tag on)
 *                     idle ≥ action-time ──► "Are you still there?" check
 *                     no answer within timeout ──► actions run (location saved)
 *   any real activity ──► not AFK, check closed, sent back to the saved location
 * </pre>
 */
public final class AfkManager {

    private final AntiAfkPlugin plugin;
    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    /** watched player -> staff watching their debug output */
    private final Map<UUID, Set<UUID>> debugWatchers = new HashMap<>();
    private final NamespacedKey returnKey;
    private BukkitTask task;
    /** True while actions are running, so commands the actions run don't count as activity. */
    private boolean runningActions;

    public AfkManager(AntiAfkPlugin plugin) {
        this.plugin = plugin;
        this.returnKey = new NamespacedKey(plugin, "return-location");
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
        sessions.values().forEach(s -> s.rebuildDetectors(settings));
    }

    // ------------------------------------------------------------------ sessions

    public void join(Player player) {
        PlayerSession session = new PlayerSession(player.getUniqueId(), System.currentTimeMillis(), plugin.settings());
        session.returnLocation = loadReturnLocation(player);
        session.movement.reset(player.getX(), player.getY(), player.getZ());
        sessions.put(player.getUniqueId(), session);
    }

    public void quit(Player player) {
        PlayerSession session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        if (session.checkStartedAt != 0) closeCheck(player, session);
        // Keep the return spot across a relog: they come back in the AFK area and return on activity.
        saveReturnLocation(player, session.returnLocation);
        debugWatchers.remove(player.getUniqueId());
        debugWatchers.values().forEach(w -> w.remove(player.getUniqueId()));
    }

    public PlayerSession session(UUID uuid) {
        return sessions.get(uuid);
    }

    public List<Player> afkPlayers() {
        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> {
                    PlayerSession s = sessions.get(p.getUniqueId());
                    return s != null && s.afk;
                })
                .map(p -> (Player) p)
                .toList();
    }

    // ------------------------------------------------------------------ activity

    /** A real, player-driven action. Resets the AFK timer. */
    public void counted(Player player, String kind, String detail) {
        if (runningActions) return;
        PlayerSession s = sessions.get(player.getUniqueId());
        if (s == null) return;
        long now = System.currentTimeMillis();
        s.lastActivity = now;
        s.lastActivityKind = kind;
        debug(player, s, kind, true, detail, now);

        if (s.checkStartedAt != 0) closeCheck(player, s);
        if (s.afk) setAfk(player, s, false);
        s.actioned = false;

        if (s.returnLocation != null) {
            Location target = s.returnLocation;
            s.returnLocation = null;
            saveReturnLocation(player, null);
            if (plugin.settings().returnEnabled && target.getWorld() != null) {
                plugin.messenger().sendRaw(player, plugin.settings().returnMessage, vars(player, s, now), player);
                player.teleportAsync(target);
            }
        }
    }

    /** Something happened but doesn't count (carried by water, repeated action, auto-clicker...). */
    public void ignored(Player player, String kind, String reason) {
        PlayerSession s = sessions.get(player.getUniqueId());
        if (s == null) return;
        s.lastIgnored.put(kind, reason);
        debug(player, s, kind, false, reason, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ timeline

    private void tick() {
        Settings settings = plugin.settings();
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerSession s = sessions.get(player.getUniqueId());
            if (s == null) {
                join(player);
                continue;
            }

            boolean exempt = player.hasPermission("antiafk.bypass")
                    || (settings.exemptOps && player.isOp())
                    || settings.exemptGameModes.contains(player.getGameMode())
                    || !settings.isCheckedWorld(player.getWorld().getName());
            if (exempt) {
                // Hold the timer still. This doesn't trigger a return; only real activity does.
                s.lastActivity = now;
                if (s.checkStartedAt != 0) closeCheck(player, s);
                if (s.afk) setAfk(player, s, false);
                continue;
            }

            long idle = s.idleMillis(now);
            if (!s.afk && idle >= settings.afkTime.toMillis()) {
                setAfk(player, s, true);
            }

            if (s.checkStartedAt != 0) {
                if (now - s.checkStartedAt >= settings.checkTimeout.toMillis()) {
                    closeCheck(player, s);
                    runActions(player, s, now);
                }
            } else if (!s.actioned && idle >= settings.actionTime.toMillis()) {
                if (settings.checkEnabled) {
                    startCheck(player, s, now);
                } else {
                    runActions(player, s, now);
                }
            }
        }
    }

    private void setAfk(Player player, PlayerSession s, boolean afk) {
        s.afk = afk;
        long now = System.currentTimeMillis();
        if (afk) s.afkSince = now;
        Map<String, String> vars = vars(player, s, now);
        Messenger messenger = plugin.messenger();
        messenger.send(player, afk ? "now-afk" : "no-longer-afk", vars, player);
        messenger.broadcast(afk ? "broadcast-afk" : "broadcast-back", vars, player);
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
            counted(player, "check", "answered the AFK check");
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
        }
        plugin.getLogger().info(player.getName() + " was AFK for " + TimeParser.format(Duration.ofMillis(s.idleMillis(now)))
                + " and didn't answer the check. Running AFK actions.");

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
        } catch (Exception e) {
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
        Location target = new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                p.length == 6 ? Float.parseFloat(p[4]) : 0f, p.length == 6 ? Float.parseFloat(p[5]) : 0f);
        player.leaveVehicle();
        player.teleportAsync(target);
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

    private void debug(Player player, PlayerSession s, String kind, boolean counted, String detail, long now) {
        Set<UUID> watchers = debugWatchers.get(player.getUniqueId());
        if (watchers == null || watchers.isEmpty()) return;

        // At most one line per kind and result per second; movement fires many times a second.
        String key = kind + counted;
        Long last = s.lastDebug.get(key);
        if (last != null && now - last < 1000) return;
        s.lastDebug.put(key, now);

        String line = "<dark_gray>[debug]</dark_gray> <white><player></white> <gray><kind>:</gray> "
                + (counted ? "<green>counted</green>" : "<red>ignored</red>") + " <dark_gray>(<detail>)";
        Component message = plugin.messenger().render(line, Map.of("player", player.getName(), "kind", kind, "detail", detail), null);
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

    private void saveReturnLocation(Player player, Location location) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (location == null || location.getWorld() == null) {
            data.remove(returnKey);
            return;
        }
        data.set(returnKey, PersistentDataType.STRING, location.getWorld().getUID() + ";" + location.getX() + ";"
                + location.getY() + ";" + location.getZ() + ";" + location.getYaw() + ";" + location.getPitch());
    }

    private Location loadReturnLocation(Player player) {
        String text = player.getPersistentDataContainer().get(returnKey, PersistentDataType.STRING);
        if (text == null) return null;
        try {
            String[] p = text.split(";");
            World world = Bukkit.getWorld(UUID.fromString(p[0]));
            if (world == null) return null;
            return new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (RuntimeException e) {
            player.getPersistentDataContainer().remove(returnKey);
            return null;
        }
    }
}
