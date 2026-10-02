package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

public class MailGen {
    VA_postal plugin;

    public MailGen(VA_postal instance) {
        this.plugin = instance;
    }

    public static synchronized String stack2serial(ItemStack stack) {
        String name = stack.getType().name();
        String qty = Util.int2str(stack.getAmount());
        String id = Util.int2str(stack.getTypeId());
        String durability = Util.int2str(stack.getDurability());
        return name + "," + qty + "," + id + "," + durability;
    }

    public static synchronized boolean replace_slot_by_index_cen(final int index, final ItemStack book_item) {
        Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(VA_postal.plugin, new Runnable() {
            @Override
            public void run() {
                VA_postal.central_po_inventory.setItem(index, book_item);
            }
        }, 6L);
        return true;
    }

    public static synchronized String proper(String string) {
        try {
            return string.length() > 0 ? string.substring(0, 1).toUpperCase() + string.substring(1).toLowerCase().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static synchronized String df(String string) {
        try {
            String[] parts = string.split("_");
            String name = proper(parts[0]);
            if (parts.length > 1) {
                name = name + "_" + Util.proper(parts[1]);
            }

            if (parts.length > 2) {
                name = name + "_" + Util.proper(parts[2]);
            }

            if (parts.length > 3) {
                name = name + "_" + Util.proper(parts[3]);
            }

            return name;
        } catch (Exception e) {
            return "";
        }
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
