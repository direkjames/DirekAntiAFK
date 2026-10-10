package com.direk.dkafk;

import com.direk.dkafk.command.AfkCommand;
import com.direk.dkafk.command.DkAfkCommand;
import com.direk.dkafk.core.TimeParser;
import com.direk.dkafk.hook.DkAfkExpansion;
import com.direk.dkafk.startup.StartupBanner;
import com.direk.dkcore.DkPlugin;
import com.direk.dkcore.afk.AfkService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * dkAFK - AFK detection for the dk suite. Formerly DirekAntiAFK.
 * <p>
 * Registers itself as dkCore's {@link AfkService}, so every dk plugin can ask
 * {@code DkCore.get().isAfk(uuid)} and listen to {@code DkAfkChangeEvent} without depending on dkAFK.
 * <p>
 * Author: direk james
 */
public final class DkAfk extends DkPlugin {

    /** The plugin's name before the rename. Its data folder, keys and placeholders are still read. */
    public static final String LEGACY_NAME = "DirekAntiAFK";

    private Settings settings;
    private Messenger messenger;
    private AfkManager afkManager;
    private boolean placeholderApi;
    /** afk-tag already converted for placeholders, so TAB doesn't trigger a MiniMessage parse every refresh. */
    private volatile String afkTag = "";

    @Override
    public void onLoad() {
        // Runs before dkCore's ConfigFile reads config.yml, so an old config is in place when it does.
        migrateDataFolder();
    }

    @Override
    protected void enable() {
        long start = System.currentTimeMillis();
        settings = new Settings(cfg().yaml(), getLogger());
        messenger = new Messenger(this);
        refreshTag();

        afkManager = new AfkManager(this);
        listen(new ActivityListener(this));
        afkManager.start();

        // dkCore's AFK hook: every dk plugin now gets AFK status from dkAFK.
        AfkService service = afkManager::isAfk;
        Bukkit.getServicesManager().register(AfkService.class, service, this, ServicePriority.Normal);

        DkAfkCommand command = new DkAfkCommand(this);
        AfkCommand afkCommand = new AfkCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(command.build(), "dkAFK admin commands", DkAfkCommand.ALIASES);
            event.registrar().register(afkCommand.build(), "Go AFK, or come back");
        });

        placeholderApi = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (placeholderApi) {
            new DkAfkExpansion(this, "dkafk").register();
            // Old identifier, so %antiafk_...% in TAB and other configs keeps working.
            new DkAfkExpansion(this, "antiafk").register();
        }

        new StartupBanner(this).printStartup(System.currentTimeMillis() - start);
    }

    @Override
    protected void disable() {
        if (afkManager != null) afkManager.stop();
        Bukkit.getServicesManager().unregisterAll(this);
        if (settings != null) new StartupBanner(this).printShutdown();
    }

    /** Reloads config.yml and applies it to everyone online. */
    public void reloadSettings() {
        reloadAll();
        settings = new Settings(cfg().yaml(), getLogger());
        refreshTag();
        afkManager.reload();
        logSummary();
    }

    public void logSummary() {
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

    /**
     * First start after the rename: copy plugins/DirekAntiAFK/ into plugins/dkAFK/ so the
     * owner's config carries over. The old folder is left alone and can be deleted afterwards.
     */
    private void migrateDataFolder() {
        Path target = getDataFolder().toPath();
        Path legacy = target.resolveSibling(LEGACY_NAME);
        if (!Files.isDirectory(legacy) || Files.exists(target.resolve("config.yml"))) return;

        try (Stream<Path> walk = Files.walk(legacy)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                Path copy = target.resolve(legacy.relativize(file).toString());
                if (Files.exists(copy)) continue;
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
            getLogger().info("Copied your old settings from plugins/" + LEGACY_NAME + "/ to plugins/"
                    + getDataFolder().getName() + "/. You can delete the old folder.");
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't copy settings from plugins/" + LEGACY_NAME
                    + "/. Copy config.yml over by hand.", e);
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
