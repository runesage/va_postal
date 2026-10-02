package com.vodhanel.minecraft.va_postal.config;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

public class C_Towny {
    VA_postal plugin;

    public C_Towny(VA_postal instance) {
        this.plugin = instance;
    }

    public static synchronized boolean is_towny_town_defined(String stown) {
        try {
            String spath = GetConfig.path_format("postoffice.local." + stown + ".towny");
            return GetConfig.is_parent_defined(spath);
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized void set_towny_town(String stown, String towny) {
        try {
            String spath = GetConfig.path_format("postoffice.local." + stown + ".towny.name");
            VA_postal.plugin.getConfig().set(spath, towny);
            VA_postal.plugin.saveConfig();
        } catch (Exception e) {
            Util.cinform("\u001b[1;31mProblem setting local PO owner");
        }
    }

    public static synchronized String get_towny_town(String stown) {
        if (is_towny_town_defined(stown)) {
            try {
                String spath = GetConfig.path_format("postoffice.local." + stown + ".towny.name");
                return VA_postal.plugin.getConfig().getString(spath);
            } catch (Exception e) {
                return "";
            }
        } else {
            return "";
        }
    }

    public static synchronized void del_towny_town(String stown) {
        try {
            String spath = GetConfig.path_format("postoffice.local." + stown + ".towny");
            VA_postal.plugin.getConfig().set(spath, null);
            VA_postal.plugin.saveConfig();
        } catch (Exception e) {
            Util.cinform("\u001b[1;31mProblem deleting local PO owner");
        }
    }

    public static synchronized int towny_po_count(String towny) {
        String stown = "";
        int hits = 0;
        String path = GetConfig.path_format("postoffice.local");
        ConfigurationSection cs = VA_postal.configsettings.getConfigurationSection(path);
        if (cs != null) {
            Set<String> child_keys = cs.getKeys(false);
            if (child_keys != null && child_keys.size() > 0) {
                Object[] a_child_keys = child_keys.toArray();

                for (Object a_child_key : a_child_keys) {
                    stown = a_child_key.toString();
                    if (is_towny_town_defined(stown) && get_towny_town(stown).equalsIgnoreCase(towny)) {
                        hits++;
                    }
                }
            }
        }

        return hits;
    }

    public static synchronized int towny_addr_count(String towny) {
        String stown = "";
        int hits = 0;
        String path = GetConfig.path_format("postoffice.local");
        ConfigurationSection cs = VA_postal.configsettings.getConfigurationSection(path);
        if (cs != null) {
            Set<String> child_keys = cs.getKeys(false);
            if (child_keys.size() > 0) {
                Object[] a_child_keys = child_keys.toArray();

                for (Object a_child_key : a_child_keys) {
                    stown = a_child_key.toString();
                    if (is_towny_town_defined(stown) && get_towny_town(stown).equalsIgnoreCase(towny)) {
                        hits += C_Address.address_count(stown);
                    }
                }
            }
        }

        return hits;
    }

    public static synchronized int blocks_per_po() {
        String spath = GetConfig.path_format("settings.towny.blocks_per_po");

        try {
            String str = VA_postal.plugin.getConfig().getString(spath);
            return Integer.parseInt(str);
        } catch (Exception e) {
            return 96;
        }
    }

    public static synchronized int blocks_per_addr() {
        String spath = GetConfig.path_format("settings.towny.blocks_per_addr");

        try {
            String str = VA_postal.plugin.getConfig().getString(spath);
            return Integer.parseInt(str);
        } catch (Exception e) {
            return 8;
        }
    }

    public static synchronized int wypnts_per_addr() {
        String spath = GetConfig.path_format("settings.towny.wypnts_per_addr");

        try {
            String str = VA_postal.plugin.getConfig().getString(spath);
            return Integer.parseInt(str);
        } catch (Exception e) {
            return 30;
        }
    }
}
