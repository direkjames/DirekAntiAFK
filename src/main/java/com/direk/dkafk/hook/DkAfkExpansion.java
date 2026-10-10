package com.direk.dkafk.hook;

import com.direk.dkafk.DkAfk;
import com.direk.dkafk.PlayerSession;
import com.direk.dkafk.core.TimeParser;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.time.Duration;
import java.util.Locale;

/**
 * PlaceholderAPI placeholders:
 * <ul>
 *     <li>{@code %dkafk_tag%} — the afk-tag while AFK, otherwise empty (for TAB suffixes)</li>
 *     <li>{@code %dkafk_afk%} — {@code true} or {@code false}</li>
 *     <li>{@code %dkafk_idle%} — time since last real activity, e.g. "4m 30s"</li>
 *     <li>{@code %dkafk_idle_seconds%} — the same in seconds</li>
 *     <li>{@code %dkafk_afk_time%} — idle time while AFK, otherwise empty</li>
 * </ul>
 * Registered twice: as {@code dkafk}, and as {@code antiafk} so DirekAntiAFK-era configs keep working.
 * <p>
 * Author: direk james
 */
public final class DkAfkExpansion extends PlaceholderExpansion {

    private final DkAfk plugin;
    private final String identifier;

    public DkAfkExpansion(DkAfk plugin, String identifier) {
        this.plugin = plugin;
        this.identifier = identifier;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        String authors = String.join(", ", plugin.getPluginMeta().getAuthors());
        return authors.isEmpty() ? "direk james" : authors;
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
