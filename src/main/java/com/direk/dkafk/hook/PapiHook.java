package com.direk.dkafk.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

/** Kept in its own class so PlaceholderAPI is only loaded when it's installed. */
public final class PapiHook {

    private PapiHook() {
    }

    public static String apply(OfflinePlayer player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
