package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.mail.SignManip;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/** The clerks' clicks, the counter menu's clicks, and chat answers to the counter's questions. */
public final class ClerkListener implements Listener {
    @EventHandler(priority = EventPriority.HIGH)
    public void on_clerk_click(NPCRightClickEvent event) {
        String office = Clerks.office_of(event.getNPC());
        if (office == null) {
            return;
        }
        event.setCancelled(true);
        CounterMenu.open(event.getClicker(), office);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void on_menu_click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof CounterMenu menu)) {
            return;
        }
        event.setCancelled(true); // nothing moves in or out of the counter
        if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory()) {
            menu.click(player, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void on_menu_drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof CounterMenu) {
            event.setCancelled(true);
        }
    }

    // LOWEST, so a chat plugin never shows a typed house name or price to everyone.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void on_chat(AsyncChatEvent event) {
        String typed = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (Forms.answer(event.getPlayer(), typed)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void on_quit(PlayerQuitEvent event) {
        Forms.forget(event.getPlayer().getUniqueId());
    }

    /** Without clerks ({@code Settings.Clerks.Local: false}), right-clicking an office's sign opens its counter. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void on_sign_click(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || Clerks.enabled()) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || !(clicked.getState() instanceof Sign)) {
            return;
        }
        Block chest = SignManip.sign2chest_block(clicked);
        if (chest == null) {
            return;
        }
        for (String office : Clerks.offices()) {
            if (chest.equals(Clerks.office_chest(office))) {
                event.setCancelled(true);
                CounterMenu.open(event.getPlayer(), office);
                return;
            }
        }
    }
}
