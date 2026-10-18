package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.mail.ChestManip;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;

import java.util.List;

/**
 * Protects Central's chest and its sign (docs/design/central-office.md §2, "Protecting Central"): only admins can
 * break them; explosions leave them standing, pistons can't move them, hoppers can't take from or put into the chest,
 * fire can't burn the sign, mobs can't change them. Attempts by players are logged ({@code Settings.Central.Report_grief}).
 * Postal does this itself, so Central is safe without a protection plugin. (The clerk protects itself: Clerks.)
 */
public final class CentralGuard implements Listener {
    private static String cached_for;
    private static Block chest;
    private static Block sign;

    /** True if {@code b} is Central's chest or its sign. */
    public static synchronized boolean protects(Block b) {
        if (b == null) {
            return false;
        }
        String central = C_Postoffice.get_central_po_location();
        if (central == null || "null".equals(central)) {
            return false;
        }
        if (!central.equals(cached_for) || chest == null || !ChestManip.is_chest(chest.getType())) {
            cached_for = central;
            chest = Clerks.office_chest(Clerks.CENTRAL);
            sign = chest == null ? null : ChestManip.chest2sign_block(chest);
        }
        return b.equals(chest) || b.equals(sign);
    }

    /**
     * True (and the player told, and the attempt logged) if {@code player} may not break {@code b} because it's
     * Central's chest or sign. Postal's own left-click breaking (BukkitListener) asks this too.
     */
    public static boolean refuse(Player player, Block b) {
        if (!protects(b) || player.hasPermission("postal.admin")) {
            return false;
        }
        Util.pinform(player, "&7Central's chest and sign belong to the Post Office: only staff can take them down.");
        if (GetConfig.bool_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.central.report_grief"), true)) {
            Util.cinform("[Postal] " + player.getName() + " tried to break Central's " + (b.equals(chest) ? "chest" : "sign")
                    + " at " + b.getX() + " " + b.getY() + " " + b.getZ());
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_break(BlockBreakEvent event) {
        if (refuse(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_entity_explode(EntityExplodeEvent event) {
        event.blockList().removeIf(CentralGuard::protects);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_block_explode(BlockExplodeEvent event) {
        event.blockList().removeIf(CentralGuard::protects);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_piston_extend(BlockPistonExtendEvent event) {
        if (any(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_piston_retract(BlockPistonRetractEvent event) {
        if (any(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_burn(BlockBurnEvent event) {
        if (protects(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_change(EntityChangeBlockEvent event) {
        if (protects(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    // Hoppers, droppers and hopper minecarts: nothing in or out of Central's chest but what Postal moves itself.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_item_move(InventoryMoveItemEvent event) {
        if (central(event.getSource()) || central(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    private static boolean central(Inventory inventory) {
        Location at = inventory == null ? null : inventory.getLocation();
        return at != null && protects(at.getBlock());
    }

    private static boolean any(List<Block> blocks) {
        for (Block b : blocks) {
            if (protects(b)) {
                return true;
            }
        }
        return false;
    }
}
