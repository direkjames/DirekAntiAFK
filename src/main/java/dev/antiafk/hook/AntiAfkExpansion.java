package dev.antiafk.hook;

import dev.antiafk.AntiAfkPlugin;
import dev.antiafk.PlayerSession;
import dev.antiafk.core.TimeParser;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.time.Duration;
import java.util.Locale;

/**
 * PlaceholderAPI placeholders:
 * <ul>
 *     <li>{@code %antiafk_tag%} — the afk-tag while AFK, otherwise empty (for TAB suffixes)</li>
 *     <li>{@code %antiafk_afk%} — {@code true} or {@code false}</li>
 *     <li>{@code %antiafk_idle%} — time since last real activity, e.g. "4m 30s"</li>
 *     <li>{@code %antiafk_idle_seconds%} — the same in seconds</li>
 *     <li>{@code %antiafk_afk_time%} — idle time while AFK, otherwise empty</li>
 * </ul>
 */
public final class AntiAfkExpansion extends PlaceholderExpansion {

    private final AntiAfkPlugin plugin;

    public AntiAfkExpansion(AntiAfkPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "antiafk";
    }

    @Override
    public String getAuthor() {
        String authors = String.join(", ", plugin.getPluginMeta().getAuthors());
        return authors.isEmpty() ? "DirekAntiAFK" : authors;
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        PlayerSession s = plugin.afkManager().session(player.getUniqueId());
        long now = System.currentTimeMillis();
        boolean afk = s != null && s.isAfk();

        return switch (params.toLowerCase(Locale.ROOT)) {
            case "tag" -> afk ? plugin.afkTag() : "";
            case "afk" -> String.valueOf(afk);
            case "idle" -> s == null ? "" : TimeParser.format(Duration.ofMillis(s.idleMillis(now)));
            case "idle_seconds" -> s == null ? "0" : String.valueOf(s.idleMillis(now) / 1000);
            case "afk_time" -> afk ? TimeParser.format(Duration.ofMillis(s.idleMillis(now))) : "";
            default -> null;
        };
    }
}
