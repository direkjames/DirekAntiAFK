package dev.antiafk.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.antiafk.AntiAfkPlugin;
import dev.antiafk.Messenger;
import dev.antiafk.PlayerSession;
import dev.antiafk.core.ActivityKind;
import dev.antiafk.core.TimeParser;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * /antiafk
 * <pre>
 *   reload          - reload config.yml
 *   list            - who is AFK
 *   check &lt;player&gt;  - full AFK status and why recent actions were ignored
 *   debug &lt;player&gt;  - live feed of what counts / doesn't count for a player
 * </pre>
 */
public final class AntiAfkCommand {

    public static final String NAME = "antiafk";
    public static final List<String> ALIASES = List.of("aafk");

    private final AntiAfkPlugin plugin;

    public AntiAfkCommand(AntiAfkPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(NAME)
                .requires(src -> src.getSender().hasPermission("antiafk.admin"))
                .then(Commands.literal("reload").executes(this::reload))
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("check")
                        .then(Commands.argument("player", ArgumentTypes.player()).executes(this::check)))
                .then(Commands.literal("debug")
                        .requires(src -> src.getSender() instanceof Player)
                        .then(Commands.argument("player", ArgumentTypes.player()).executes(this::debug)))
                .build();
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        plugin.reloadSettings();
        plugin.messenger().send(sender, "reloaded", Map.of(), null);
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Messenger messenger = plugin.messenger();
        List<Player> afk = plugin.afkManager().afkPlayers();
        if (afk.isEmpty()) {
            messenger.send(sender, "list-empty", Map.of(), null);
            return Command.SINGLE_SUCCESS;
        }
        long now = System.currentTimeMillis();
        messenger.send(sender, "list-header", Map.of("count", String.valueOf(afk.size())), null);
        for (Player player : afk) {
            PlayerSession s = plugin.afkManager().session(player.getUniqueId());
            if (s == null) continue;
            messenger.send(sender, "list-entry", Map.of(
                    "player", player.getName(),
                    "afk_time", TimeParser.format(Duration.ofMillis(s.idleMillis(now)))), player);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int check(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolve(ctx);
        Messenger messenger = plugin.messenger();
        PlayerSession s = target == null ? null : plugin.afkManager().session(target.getUniqueId());
        if (s == null) {
            messenger.send(sender, "player-not-found", Map.of(), null);
            return 0;
        }

        long now = System.currentTimeMillis();
        String exempt = plugin.afkManager().exemptReason(target);
        String status = exempt != null ? "<yellow>never checked" : s.isAfk() ? "<red>AFK" : "<green>Active";

        line(sender, "<prefix><white><player></white> <gray>is " + status, Map.of("player", target.getName()));
        if (exempt != null) {
            line(sender, " <yellow>Exempt: <player> <exempt>", Map.of("player", target.getName(), "exempt", exempt));
        }
        line(sender, " <gray>Idle for: <white><idle></white> (last activity: <white><kind></white>, last real input <white><real></white> ago)", Map.of(
                "idle", TimeParser.format(Duration.ofMillis(s.idleMillis(now))),
                "kind", s.lastActivityKind().label,
                "real", TimeParser.format(Duration.ofMillis(Math.max(0, now - s.lastRealInput())))));
        if (s.isAfk() && !s.actionsRan()) {
            line(sender, " <gray>Check at: <white><at></white> idle <dark_gray>(picked from action-time <range>)", Map.of(
                    "at", TimeParser.format(Duration.ofMillis(s.actionAtMillis())),
                    "range", plugin.settings().actionTime.toString()));
        }
        line(sender, " <gray>Check showing: <white><check></white> <dark_gray>|</dark_gray> <gray>Actions ran: <white><ran></white> "
                + "<dark_gray>|</dark_gray> <gray>Return pending: <white><ret>", Map.of(
                "check", yesNo(s.isCheckShowing()), "ran", yesNo(s.actionsRan()), "ret", yesNo(s.hasReturnLocation())));
        line(sender, " <gray>Auto-clicker timing: attack <white><a></white>, use <white><u>", Map.of(
                "a", s.isAttackClickingRobotic() ? "flagged" : "ok",
                "u", s.isUseClickingRobotic() ? "flagged" : "ok"));

        Map<ActivityKind, String> ignored = s.lastIgnored();
        if (!ignored.isEmpty()) {
            line(sender, " <gray>Last ignored:", Map.of());
            ignored.forEach((kind, reason) ->
                    line(sender, "  <dark_gray>-</dark_gray> <white><kind></white><gray>: <reason>",
                            Map.of("kind", kind.label, "reason", reason)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int debug(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!(ctx.getSource().getSender() instanceof Player watcher)) return 0;
        Player target = resolve(ctx);
        if (target == null) {
            plugin.messenger().send(watcher, "player-not-found", Map.of(), null);
            return 0;
        }
        boolean on = plugin.afkManager().toggleDebug(watcher.getUniqueId(), target.getUniqueId());
        plugin.messenger().send(watcher, on ? "debug-on" : "debug-off", Map.of("player", target.getName()), target);
        String exempt = plugin.afkManager().exemptReason(target);
        if (on && exempt != null) {
            line(watcher, "<prefix><yellow>Note: <player> <exempt>, so they're never marked AFK.",
                    Map.of("player", target.getName(), "exempt", exempt));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static Player resolve(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<Player> players = ctx.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        return players.isEmpty() ? null : players.getFirst();
    }

    private void line(CommandSender sender, String text, Map<String, String> vars) {
        sender.sendMessage(plugin.messenger().render(text, vars, null));
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}
