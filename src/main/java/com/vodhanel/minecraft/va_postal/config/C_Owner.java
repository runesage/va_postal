package com.vodhanel.minecraft.va_postal.config;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.AnsiColor;
import com.vodhanel.minecraft.va_postal.common.Util;
import org.bukkit.entity.Player;

import java.util.UUID;

public class C_Owner {
    VA_postal plugin;

    public C_Owner(VA_postal instance) {
        plugin = instance;
    }

    public static synchronized boolean does_player_own_anything(Player player) {
        Player owner;
        String stown;
        String saddress;
        String[] town_list;
        try {
            town_list = C_Arrays.town_list();
        } catch (Exception e) {
            return false;
        }
        if ((town_list != null) && (town_list.length > 0)) {
            for (String aTown_list : town_list) {
                try {
                    stown = aTown_list;

                    if ((C_Postoffice.is_local_po_name_defined(stown)) &&
                            (is_local_po_owner_defined(stown))) {
                        owner = get_owner_local_po(stown);
                        if (owner == player) {
                            return true;
                        }
                    }


                    String[] addr_list;
                    try {
                        addr_list = C_Arrays.addresses_list(stown);
                    } catch (Exception e) {
                        Util.dinform(AnsiColor.RED + "ADDRESSES LIST FAILED: " + e);
                        return false;
                    }
                    if ((addr_list != null) && (addr_list.length > 0)) {
                        for (String anAddr_list : addr_list) {
                            try {
                                saddress = anAddr_list;
                                if (C_Address.is_address_defined(stown, saddress)) {
                                    if (is_address_owner_defined(stown, saddress)) {
                                        owner = get_owner_address(stown, saddress);
                                        if (owner == player) {
                                            return true;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                return false;
                            }
                        }
                    }
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return false;
    }

    public static synchronized boolean is_local_po_owner_defined(String stown) {
        try {
            String spath = GetConfig.path_format("postoffice.local." + stown + ".owner");
            return GetConfig.is_parent_defined(spath);
        } catch (Exception ignored) {
        }
        return false;
    }

    public static synchronized void set_owner_local_po(String stown, Player owner) {
        try {
            String spath = GetConfig.path_format("postoffice.local." + stown + ".owner.uuid");
            VA_postal.plugin.getConfig().set(spath, owner.getUniqueId().toString());
            VA_postal.plugin.saveConfig();
        } catch (Exception e) {
            Util.cinform(AnsiColor.RED + "Problem setting local PO owner");
        }
    }

    public static synchronized Player get_owner_local_po(String stown) {
        try {
            return Util.UUID2Player(owner_uuid("postoffice.local." + stown + ".owner"));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Owner UUID of a local post office, or null for none/server. Works whether or not the owner is online. */
    public static synchronized UUID get_owner_local_po_id(String stown) {
        return parse_owner(owner_uuid("postoffice.local." + stown + ".owner"));
    }

    public static synchronized void del_owner_local_po(String stown) {
        try {
            String spath = GetConfig.path_format("postoffice.local." + stown + ".owner");
            VA_postal.plugin.getConfig().set(spath, null);
            VA_postal.plugin.saveConfig();
        } catch (Exception e) {
            Util.cinform(AnsiColor.RED + "Problem deleting local PO owner");
        }
    }

    public static synchronized boolean is_address_owner_defined(String stown, String saddress) {
        try {
            String spath = GetConfig.path_format("address." + stown + "." + saddress + ".owner");
            return GetConfig.is_parent_defined(spath);
        } catch (Exception ignored) {
        }
        return false;
    }

    public static synchronized void set_owner_address(String stown, String saddress, Player sowner) {
        try {
            String spath = GetConfig.path_format("address." + stown + "." + saddress + ".owner.uuid");
            VA_postal.plugin.getConfig().set(spath, sowner.getUniqueId().toString());
            VA_postal.plugin.saveConfig();
        } catch (Exception e) {
            Util.cinform(AnsiColor.RED + "Problem setting owner address");
        }
    }

    public static synchronized Player get_owner_address(String stown, String saddress) {
        try {
            return Util.UUID2Player(owner_uuid("address." + stown + "." + saddress + ".owner"));
        } catch (Exception e) {
            return null;
        }

    }

    /** Owner UUID of an address, or null for none/server. Works whether or not the owner is online. */
    public static synchronized UUID get_owner_address_id(String stown, String saddress) {
        return parse_owner(owner_uuid("address." + stown + "." + saddress + ".owner"));
    }

    /**
     * The owner UUID stored under {@code owner_path}.uuid. v4 configs stored the owner's name under
     * {@code owner_path}.name instead; that is converted to a UUID the first time it's read (from the
     * server's known players only, so it never blocks on a web lookup) and saved.
     */
    private static String owner_uuid(String owner_path) {
        String uuid_path = GetConfig.path_format(owner_path + ".uuid");
        String sid = VA_postal.plugin.getConfig().getString(uuid_path);
        if (sid != null) {
            return sid;
        }
        String name = VA_postal.plugin.getConfig().getString(GetConfig.path_format(owner_path + ".name"));
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        if ("server".equalsIgnoreCase(name.trim())) {
            return VA_postal.SERVER_ID.toString();
        }
        org.bukkit.OfflinePlayer known = org.bukkit.Bukkit.getOfflinePlayerIfCached(name.trim());
        if (known == null) {
            Util.cinform(AnsiColor.RED + "[Postal] Unknown v4 owner '" + name + "' at " + owner_path + "; leaving it unowned");
            return null;
        }
        VA_postal.plugin.getConfig().set(uuid_path, known.getUniqueId().toString());
        VA_postal.plugin.saveConfig();
        Util.cinform("[Postal] Migrated v4 owner '" + name + "' at " + owner_path + " to " + known.getUniqueId());
        return known.getUniqueId().toString();
    }

    private static UUID parse_owner(String sid) {
        try {
            if (sid == null) {
                return null;
            }
            UUID id = UUID.fromString(sid.trim());
            return VA_postal.SERVER_ID.equals(id) ? null : id;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static synchronized void del_owner_address(String stown, String saddress) {
        try {
            String spath = GetConfig.path_format("address." + stown + "." + saddress + ".owner");
            VA_postal.plugin.getConfig().set(spath, null);
            VA_postal.plugin.saveConfig();
        } catch (Exception e) {
            Util.cinform(AnsiColor.RED + "Problem deleting owner address");
        }
    }
}
