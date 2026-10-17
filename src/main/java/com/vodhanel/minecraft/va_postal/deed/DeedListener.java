package com.vodhanel.minecraft.va_postal.deed;

import com.vodhanel.minecraft.va_postal.mail.ChestManip;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Right-clicking a chest while holding a deed uses the deed on it, instead of opening the chest. */
public final class DeedListener implements Listener {
    // HIGH, ignoring cancelled clicks: a protection plugin that refuses the player this chest has had its say.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || DeedDocument.read(event.getItem()) == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !ChestManip.is_chest(block.getType())) {
            return;
        }
        event.setCancelled(true);
        Deeds.use(event.getPlayer(), block, event.getItem());
    }
}
