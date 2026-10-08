package dev.antiafk;

import dev.antiafk.core.MovementTracker;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Locale;

/**
 * Turns everything a player does into "counted" (real activity) or "ignored" (doesn't reset the AFK timer).
 * <p>
 * Never counted: being moved by water, bubble columns, minecarts, boats, pistons or other entities;
 * jumping or walking in place; walking in loops; repeating the exact same action for too long;
 * clicking with machine-like timing.
 */
public final class ActivityListener implements Listener {

    private final AntiAfkPlugin plugin;

    public ActivityListener(AntiAfkPlugin plugin) {
        this.plugin = plugin;
    }

    private AfkManager afk() {
        return plugin.afkManager();
    }

    // ------------------------------------------------------------------ join / quit / teleport

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        afk().join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        afk().quit(event.getPlayer());
    }

    /** Teleports aren't activity (warps, /is go, the AFK area, our own return). Start tracking from the new spot. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerSession s = afk().session(event.getPlayer().getUniqueId());
        if (s == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        s.movement.reset(to.getX(), to.getY(), to.getZ());
        // The warp happened (or they were sent back): let them move again.
        // A tiny "teleport" is just the freeze holding them in place, so ignore that.
        boolean realTeleport = from.getWorld() != to.getWorld() || from.distanceSquared(to) > 4;
        if (realTeleport) afk().unfreeze(s);
    }

    // ------------------------------------------------------------------ freeze while a warp warms up

    /**
     * Runs first: while frozen, keep the player's position but let them turn their head.
     * Water, bubble columns and pistons then can't move them, so a warp warmup isn't cancelled.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMoveWhileFrozen(PlayerMoveEvent event) {
        PlayerSession s = afk().session(event.getPlayer().getUniqueId());
        if (s == null || !s.isFrozen(System.currentTimeMillis())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location held = from.clone();
            held.setYaw(to.getYaw());
            held.setPitch(to.getPitch());
            event.setTo(held);
        }
    }

    /** Don't let a passing minecart or boat pick a frozen player back up. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!(event.getEntered() instanceof Player player)) return;
        PlayerSession s = afk().session(player.getUniqueId());
        if (s != null && s.isFrozen(System.currentTimeMillis())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerSession s = afk().session(event.getPlayer().getUniqueId());
        Location to = event.getRespawnLocation();
        if (s != null) s.movement.reset(to.getX(), to.getY(), to.getZ());
    }

    // ------------------------------------------------------------------ looking and moving

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerSession s = afk().session(player.getUniqueId());
        if (s == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
            if (s.look.accept(to.getYaw(), to.getPitch())) {
                afk().counted(player, "look", "turned the camera");
            } else {
                afk().ignored(player, "look", "tiny turn or a direction used recently");
            }
        }

        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            movement(player, s, to);
        }
    }

    /** Players riding something (boat, minecart, horse) don't always get PlayerMoveEvent. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                PlayerSession s = afk().session(player.getUniqueId());
                if (s != null) movement(player, s, event.getTo());
            }
        }
    }

    private void movement(Player player, PlayerSession s, Location to) {
        Input input = player.getCurrentInput();
        boolean keys = input.isForward() || input.isBackward() || input.isLeft() || input.isRight() || input.isJump();

        MovementTracker.Result result = s.movement.accept(to.getX(), to.getY(), to.getZ(), keys);
        switch (result) {
            case COUNTED -> afk().counted(player, "move", "walked somewhere new");
            case NO_INPUT -> afk().ignored(player, "move", "moved without pressing keys (water, vehicle, piston or pushed)");
            case CONFINED -> afk().ignored(player, "move", "staying in the same small area");
            case LOOP -> afk().ignored(player, "move", "came back to a spot visited recently (loop)");
        }
    }

    // ------------------------------------------------------------------ chat and commands

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim().toLowerCase(Locale.ROOT);
        // Chat arrives off the main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            action(player, "chat", "chat:" + text, "sent a chat message", "the same chat message over and over");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String full = event.getMessage().trim().toLowerCase(Locale.ROOT);
        String label = full.split(" ", 2)[0].replaceFirst("^/", "");
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1); // essentials:afk -> afk
        if (plugin.settings().ignoredCommands.contains(label)) {
            afk().ignored(event.getPlayer(), "command", "/" + label + " is in ignored-commands");
            return;
        }
        action(event.getPlayer(), "command", "cmd:" + full, "ran a command", "the same command over and over");
    }

    // ------------------------------------------------------------------ world interaction

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        action(event.getPlayer(), "break", "break:" + coords(event.getBlock()),
                "broke a block", "breaking the same block non-stop (e.g. holding left-click on the oneblock)");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        action(event.getPlayer(), "place", "place:" + coords(event.getBlock()),
                "placed a block", "placing on the same spot non-stop");
    }

    /** Not ignoreCancelled: clicking air arrives already cancelled. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL) return; // stepping on plates/farmland isn't input
        if (event.getHand() == EquipmentSlot.OFF_HAND) return; // one click fires once per hand
        Player player = event.getPlayer();
        PlayerSession s = afk().session(player.getUniqueId());
        if (s == null) return;

        boolean right = event.getAction().isRightClick();
        String where = event.getClickedBlock() == null ? "air" : coords(event.getClickedBlock());
        String key = (right ? "use:" : "hit:") + where;
        click(player, s, right, "interact", key, "clicked", "clicking the same spot non-stop");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        Player player = event.getPlayer();
        PlayerSession s = afk().session(player.getUniqueId());
        if (s == null) return;
        click(player, s, true, "interact", "use-entity:" + event.getRightClicked().getType(),
                "used an entity", "using the same kind of entity non-stop");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Player player = null;
        if (event.getDamager() instanceof Player p) {
            player = p;
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            player = p;
        }
        if (player == null) return;
        PlayerSession s = afk().session(player.getUniqueId());
        if (s == null) return;
        // Keyed by mob type: a mob grinder sends many different mobs of the same type
        click(player, s, false, "attack", "attack:" + event.getEntity().getType(),
                "attacked", "attacking the same kind of mob non-stop (mob grinder)");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        action(event.getPlayer(), "fish", "fish:" + coords(event.getPlayer().getLocation().getBlock()),
                "fished", "fishing from the same spot non-stop");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        action(event.getPlayer(), "eat", "eat:" + event.getItem().getType(), "ate or drank", "eating the same item non-stop");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        action(event.getPlayer(), "drop", "drop:" + event.getItemDrop().getItemStack().getType(),
                "dropped an item", "dropping the same item non-stop");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        action(player, "inventory", "inv:" + event.getRawSlot() + ":" + event.getClick(),
                "used an inventory", "clicking the same slot non-stop");
    }

    // ------------------------------------------------------------------ helpers

    /** An action that counts unless the exact same thing has been repeated non-stop for too long. */
    private void action(Player player, String kind, String key, String countedText, String repeatedText) {
        PlayerSession s = afk().session(player.getUniqueId());
        if (s == null) return;
        if (s.repeats.accept(key, System.currentTimeMillis())) {
            afk().counted(player, kind, countedText);
        } else {
            afk().ignored(player, kind, repeatedText);
        }
    }

    /** Like {@link #action}, but also ignored when the clicks are machine-evenly spaced. */
    private void click(Player player, PlayerSession s, boolean rightClick, String kind, String key,
                       String countedText, String repeatedText) {
        long now = System.currentTimeMillis();
        boolean human = (rightClick ? s.useClicks : s.attackClicks).accept(now);
        boolean fresh = s.repeats.accept(key, now);
        if (!human) {
            afk().ignored(player, kind, "clicks are evenly timed (auto-clicker or held button)");
        } else if (!fresh) {
            afk().ignored(player, kind, repeatedText);
        } else {
            afk().counted(player, kind, countedText);
        }
    }

    private static String coords(Block block) {
        return block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
    }
}
