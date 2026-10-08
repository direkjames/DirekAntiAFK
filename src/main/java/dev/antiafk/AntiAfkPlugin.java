package dev.antiafk;

import dev.antiafk.command.AntiAfkCommand;
import dev.antiafk.core.TimeParser;
import dev.antiafk.hook.AntiAfkExpansion;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class AntiAfkPlugin extends JavaPlugin {

    private Settings settings;
    private Messenger messenger;
    private AfkManager afkManager;
    private boolean placeholderApi;
    /** afk-tag already converted for placeholders, so TAB doesn't trigger a MiniMessage parse every refresh. */
    private volatile String afkTag = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
        messenger = new Messenger(this);
        refreshTag();

        afkManager = new AfkManager(this);
        getServer().getPluginManager().registerEvents(new ActivityListener(this), this);
        afkManager.start();

        logSummary();

        AntiAfkCommand command = new AntiAfkCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command.build(), "DirekAntiAFK admin commands", AntiAfkCommand.ALIASES));

        placeholderApi = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (placeholderApi) {
            new AntiAfkExpansion(this).register();
            getLogger().info("Hooked into PlaceholderAPI.");
        }
    }

    @Override
    public void onDisable() {
        if (afkManager != null) afkManager.stop();
    }

    public void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        refreshTag();
        afkManager.reload();
        logSummary();
    }

    private void logSummary() {
        getLogger().info("AFK after " + TimeParser.format(settings.afkTime) + ", check at " + settings.actionTime
                + (settings.exemptOps ? " (OPs are never checked)" : "") + ".");
    }

    private void refreshTag() {
        if (settings.placeholderLegacy) {
            afkTag = messenger.toLegacy(messenger.render(settings.afkTag, Map.of(), null));
        } else {
            afkTag = settings.afkTag;
        }
    }

    public Settings settings() {
        return settings;
    }

    public Messenger messenger() {
        return messenger;
    }

    public AfkManager afkManager() {
        return afkManager;
    }

    public boolean hasPlaceholderApi() {
        return placeholderApi;
    }

    public String afkTag() {
        return afkTag;
    }
}
