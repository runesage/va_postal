package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * The {@code postal:mail_id} a tracked book carries in its PersistentDataContainer
 * (docs/design/persistent-state.md §3). Players can't edit it, and every restamp must carry it over
 * ({@link #carry}), since stamping builds a fresh book from the page text.
 */
public final class MailIds {
    private static NamespacedKey key;

    private MailIds() {
    }

    private static NamespacedKey key() {
        if (key == null) {
            key = new NamespacedKey(VA_postal.plugin, "mail_id");
        }
        return key;
    }

    /** The book's mail id, or null for an untracked book. */
    public static UUID read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(key(), PersistentDataType.STRING);
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code item} carrying {@code id} (changes and returns the same stack). */
    public static ItemStack write(ItemStack item, UUID id) {
        if (item == null || id == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().set(key(), PersistentDataType.STRING, id.toString());
        item.setItemMeta(meta);
        return item;
    }

    /** Copies {@code from}'s mail id (if any) onto {@code to}, e.g. a restamped book. Returns {@code to}. */
    public static ItemStack carry(ItemStack from, ItemStack to) {
        return write(to, read(from));
    }
}
