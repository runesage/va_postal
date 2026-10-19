package com.vodhanel.minecraft.va_postal.board;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Right-clicking a sign on Central's notice board puts its panel in full in the player's chat (NoticeBoard). */
public final class BoardListener implements Listener {
    private static final long COOLDOWN_MS = 1000L;
    private final Map<UUID, Long> last = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGH)
    public void on_interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        NoticeBoard.Panel panel = NoticeBoard.panel_at(event.getClickedBlock());
        if (panel == null) {
            return;
        }
        event.setCancelled(true); // no sign editor, nothing placed against it
        long now = System.currentTimeMillis();
        Long before = last.put(event.getPlayer().getUniqueId(), now);
        if (before != null && now - before < COOLDOWN_MS) {
            return;
        }
        NoticeBoard.details(event.getPlayer(), panel);
    }
}
