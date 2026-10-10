package com.direk.dkafk.startup;

import com.direk.dkafk.DkAfk;
import com.direk.dkafk.Settings;
import com.direk.dkafk.core.TimeParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.ConsoleCommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The dkAFK banner in the console, in the same style as dkCore's:
 * logo, version, the dkCore link, hooks, the AFK timeline and startup time.
 * <p>
 * The logo is sent as plain text components (never parsed as MiniMessage), and all
 * dynamic values go in as unparsed placeholders, so characters like {@code \} and {@code <} can't
 * break the formatting.
 * <p>
 * Turn it off with "startup-banner: false" in config.yml.
 * Author: direk james
 */
public final class StartupBanner {

    private static final String[] LOGO = {
            "     _  _         _     _____  _  __",
            "  __| || | __    / \\   |  ___|| |/ /",
            " / _` || |/ /   / _ \\  | |_   | ' /",
            "| (_| ||   <   / ___ \\ |  _|  | . \\",
            " \\__,_||_|\\_\\ /_/   \\_\\|_|    |_|\\_\\"
    };

    // dkCore's purple → blue
    private static final TextColor PURPLE = TextColor.color(0x7c5cff);
    private static final TextColor BLUE = TextColor.color(0x7cc6ff);
    private static final String LINE = "<dark_gray>" + "-".repeat(56);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final DkAfk plugin;
    private final boolean enabled;

    public StartupBanner(DkAfk plugin) {
        this.plugin = plugin;
        this.enabled = plugin.cfg().yaml().getBoolean("startup-banner", true);
    }

    /** Printed once dkAFK has enabled. */
    public void printStartup(long totalMs) {
        if (!enabled) {
            plugin.getLogger().info("dkAFK v" + version() + " enabled in " + totalMs + " ms, linked to dkCore v"
                    + coreVersion() + ".");
            plugin.logSummary();
            return;
        }
        Settings s = plugin.settings();

        blank();
        line(LINE);
        logo();
        blank();
        line("  <white><bold>dkAFK</bold></white> <gray>v<v></gray>  <dark_gray>|</dark_gray>  <gray>by</gray> <#7cc6ff>direk james",
                v(version()));
        line("  <dark_gray><italic>AFK detection for the dk plugin suite");
        line(LINE);
        row("dkCore", "<green>v<v> linked</green> <dark_gray>(AFK provider for every dk plugin)", v(coreVersion()));
        row("Hooks", plugin.hasPlaceholderApi()
                ? "<green>PlaceholderAPI</green> <dark_gray>%dkafk_...% and %antiafk_...%"
                : "<yellow>PlaceholderAPI not found (placeholders are off)");
        row("AFK at", "<white><afk></white> <gray>idle, check at</gray> <white><check></white>",
                t("afk", TimeParser.format(s.afkTime)), t("check", s.actionTime));
        row("Check", s.checkEnabled
                        ? "<white><type></white> <gray>popup,</gray> <white><timeout></white> <gray>to answer"
                        : "<yellow>off (actions run straight away)",
                t("type", s.checkType.name().toLowerCase(Locale.ROOT)), t("timeout", TimeParser.format(s.checkTimeout)));
        row("Actions", s.actions.isEmpty()
                        ? "<yellow>none configured (nothing happens to AFK players)"
                        : "<white><count></white> <gray>configured, return</gray> <white><ret>",
                t("count", s.actions.size()), t("ret", s.returnEnabled ? "on" : "off"));
        row("Exempt", "<white><v>", v(exempt(s)));
        row("Status", "<green>Enabled</green> <dark_gray>in <ms> ms", t("ms", totalMs));
        line(LINE);
        blank();
    }

    /** Prints a short goodbye on shutdown. */
    public void printShutdown() {
        if (!enabled) return;
        line("<dark_gray>[</dark_gray><gradient:#7c5cff:#7cc6ff>dkAFK</gradient><dark_gray>]</dark_gray> "
                + "<gray>v<v> disabled.", v(version()));
    }

    // ---- helpers ----

    private static String exempt(Settings s) {
        List<String> parts = new ArrayList<>();
        if (s.exemptOps) parts.add("OPs");
        for (GameMode mode : s.exemptGameModes) parts.add(mode.name().toLowerCase(Locale.ROOT));
        parts.add("dkafk.bypass");
        return String.join(", ", parts);
    }

    /** Logo lines as plain text, colored purple → blue from top to bottom. */
    private void logo() {
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        for (int i = 0; i < LOGO.length; i++) {
            float progress = LOGO.length == 1 ? 0f : (float) i / (LOGO.length - 1);
            console.sendMessage(Component.text(LOGO[i], TextColor.lerp(progress, PURPLE, BLUE)));
        }
    }

    private void row(String label, String valueMiniMessage, TagResolver... values) {
        line("  <#7cc6ff>" + String.format("%-10s", label) + "</#7cc6ff><gray>" + valueMiniMessage, values);
    }

    private void line(String miniMessage, TagResolver... values) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(miniMessage, TagResolver.resolver(values)));
    }

    private void blank() {
        Bukkit.getConsoleSender().sendMessage(Component.text(" "));
    }

    /** A value placeholder {@code <v>} shown exactly as written. */
    private static TagResolver v(String value) {
        return Placeholder.unparsed("v", value);
    }

    private static TagResolver t(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    private String version() {
        return plugin.getPluginMeta().getVersion();
    }

    private String coreVersion() {
        return plugin.core().getPluginMeta().getVersion();
    }
}
