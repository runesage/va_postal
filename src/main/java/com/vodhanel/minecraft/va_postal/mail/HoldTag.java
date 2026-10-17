package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * The postage hold id on a letter or shipping label ({@code postal:hold} in the item's persistent data), linking
 * it to the money held in escrow for it. Postal rebuilds mail items as it stamps them, so every rebuild
 * {@link #carry carries} the tag over.
 */
public final class HoldTag {
    private static NamespacedKey key;

    private HoldTag() {
    }

    private static NamespacedKey key() {
        if (key == null) {
            key = new NamespacedKey(VA_postal.plugin, "hold");
        }
        return key;
    }

    /** The hold id on {@code item}, or null if it has none. */
    public static String read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key(), PersistentDataType.STRING);
    }

    public static ItemStack write(ItemStack item, String hold_id) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (hold_id == null) {
            meta.getPersistentDataContainer().remove(key());
        } else {
            meta.getPersistentDataContainer().set(key(), PersistentDataType.STRING, hold_id);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Copies {@code from}'s hold id (if any) onto {@code to}, a rebuilt copy of the same mail. Returns {@code to}. */
    public static ItemStack carry(ItemStack from, ItemStack to) {
        String id = read(from);
        return id == null ? to : write(to, id);
    }
}
