package com.vodhanel.minecraft.va_postal.clerk;

import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

/** One of the counter's menus: nothing moves in or out of it, and a click on one of its slots does something. */
interface Menu extends InventoryHolder {
    /** {@code player} clicked {@code slot} ({@code right}: with the right button). */
    void click(Player player, int slot, boolean right);
}
