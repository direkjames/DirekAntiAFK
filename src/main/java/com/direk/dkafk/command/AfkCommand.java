package com.direk.dkafk.command;

import com.direk.dkafk.DkAfk;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

/**
 * /afk - a player marks themselves AFK, or comes back. Permission: dkafk.afk.
 * <p>
 * Author: direk james
 */
public final class AfkCommand {

    public static final String NAME = "afk";

    private final DkAfk plugin;

    public AfkCommand(DkAfk plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(NAME)
                .requires(src -> src.getSender() instanceof Player player && player.hasPermission("dkafk.afk"))
                .executes(ctx -> {
                    if (ctx.getSource().getSender() instanceof Player player) {
                        plugin.afkManager().toggleManual(player);
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
