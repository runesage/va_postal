package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;


public class MailGen {
    VA_postal plugin;

    public MailGen(VA_postal instance) {
        plugin = instance;
    }

    /**
     * Parcel line format, unchanged from v4: {@code MATERIAL,qty,id,damage}. Numeric item IDs no longer
     * exist, so the id field is written as 0 and ignored; the material name is authoritative.
     */
    public static synchronized String stack2serial(ItemStack stack) {
        String name = stack.getType().name();
        String qty = Util.int2str(stack.getAmount());
        int damage = (stack.getItemMeta() instanceof Damageable) ? ((Damageable) stack.getItemMeta()).getDamage() : 0;
        return name + "," + qty + ",0," + Util.int2str(damage);
    }

    /** Rebuilds a parcel item from its serialized material name, amount and damage; null if unknown. */
    @SuppressWarnings("deprecation")
    public static ItemStack serial2stack(String name, int qty, int damage) {
        Material material = Material.matchMaterial(name);
        if (material == null) {
            // Parcels written by v4 on 1.12 carry pre-1.13 names (e.g. WOOD, SMOOTH_BRICK).
            Material legacy = Material.matchMaterial(name, true);
            if (legacy != null) {
                try {
                    material = Bukkit.getUnsafe().fromLegacy(legacy);
                } catch (RuntimeException ignored) {
                    material = null;
                }
            }
        }
        if (material == null || material.isAir() || !material.isItem()) {
            return null;
        }
        ItemStack stack = new ItemStack(material, Math.max(1, qty));
        if (damage > 0 && stack.getItemMeta() instanceof Damageable) {
            Damageable meta = (Damageable) stack.getItemMeta();
            meta.setDamage(damage);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static synchronized void replace_slot_by_index_cen(int index, final ItemStack book_item) {
        Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(VA_postal.plugin, new Runnable() {

            public void run() {
                VA_postal.central_po_inventory.setItem(index, book_item);
            }
        }, 6L);


    }

    public static synchronized String proper(String string) {
        try {
            if (string.length() > 0) {
                return string.substring(0, 1).toUpperCase() + string.substring(1).toLowerCase().trim();
            }
        } catch (Exception e) {
            return "";
        }
        return "";
    }

    public static synchronized String fixed_len(String input, int len) {
        try {
            input = input.trim();

            if (input.length() >= len) {
                return input.substring(0, len);
            }

            while (input.length() < len) {
                input = input + " ";
            }
            return input;
        } catch (Exception e) {
            String blank = "";
            for (int i = 0; i < len; i++) {
                blank = blank + " ";
            }
            return blank;
        }
    }

    public static synchronized String ifixed_len(int number, int len) {
        try {
            String input = Integer.toString(number);

            if (input.length() >= len) {
                return input.substring(0, len);
            }

            while (input.length() < len) {
                input = "0" + input;
            }
            return input;
        } catch (Exception e) {
            String blank = "";
            for (int i = 0; i < len; i++) {
                blank = blank + " ";
            }
            return blank;
        }
    }
}
