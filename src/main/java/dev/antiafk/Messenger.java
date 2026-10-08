package dev.antiafk;

import dev.antiafk.hook.PapiHook;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Map;

/**
 * Turns config text into components and sends it.
 * <p>
 * Order: PlaceholderAPI placeholders ({@code %player_name%}) are filled in first, for the player the
 * message is about, then MiniMessage formatting is applied. Values inserted from code (like a chat
 * message in debug output) are plain text and never run through PlaceholderAPI or MiniMessage.
 */
public final class Messenger {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** §-codes with §x§r§r§g§g§b§b hex colors, which TAB and most plugins understand. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final AntiAfkPlugin plugin;

    public Messenger(AntiAfkPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param context the player the text is about (for PlaceholderAPI), or null
     * @param vars    extra values for tags like {@code <count>}, inserted as plain text
     */
    public Component render(String text, Map<String, String> vars, OfflinePlayer context) {
        String filled = context != null && plugin.hasPlaceholderApi() ? PapiHook.apply(context, text) : text;
        TagResolver.Builder resolvers = TagResolver.builder()
                .resolver(Placeholder.parsed("prefix", plugin.settings().prefix));
        for (Map.Entry<String, String> var : vars.entrySet()) {
            resolvers.resolver(Placeholder.unparsed(var.getKey(), var.getValue() == null ? "" : var.getValue()));
        }
        return MM.deserialize(filled, resolvers.build());
    }

    public String toLegacy(Component component) {
        return LEGACY.serialize(component);
    }

    /** Sends {@code messages.<key>}. Does nothing if the message is empty. */
    public void send(Audience audience, String key, Map<String, String> vars, OfflinePlayer context) {
        sendRaw(audience, plugin.settings().message(key), vars, context);
    }

    /** Sends a MiniMessage string. Does nothing if it's empty. */
    public void sendRaw(Audience audience, String text, Map<String, String> vars, OfflinePlayer context) {
        if (text != null && !text.isEmpty()) audience.sendMessage(render(text, vars, context));
    }

    /** Sends {@code messages.<key>} to every player and the console. */
    public void broadcast(String key, Map<String, String> vars, OfflinePlayer context) {
        send(Bukkit.getServer(), key, vars, context);
    }
}
