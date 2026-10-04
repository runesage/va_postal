package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.BlockFacing;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;

public class SignManip {
    VA_postal plugin;

    public SignManip(VA_postal instance) {
        plugin = instance;
    }

    public static synchronized boolean is_this_a_postal_sign(Block block, int type) {
        if (block == null) {
            return false;
        }
        String key = "";
        switch (type) {
            case 0:
                key = "[Postal_";
                break;
            case 1:
                key = "[Postal_Mail]";
                break;
            case 2:
                key = "[Postal_Ship]";
                break;
            case 3:
                key = "[Postal_Accept]";
                break;
            case 4:
                key = "[Postal_Refuse]";
                break;
            default:
                key = "[Postal_";
        }
        if ((block.getState() instanceof Sign)) {
            Sign sign = (Sign) block.getState();
            if (sign.getLine(0).contains(key)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean text_exists_sign(Block block, String stext, int line) {
        if (block == null) {
            return false;
        }
        stext = stext.toLowerCase().trim();
        if ((block.getState() instanceof Sign)) {
            Sign sign = (Sign) block.getState();
            if ((line == 1) &&
                    (sign.getLine(0).toLowerCase().contains(stext))) {
                return true;
            }

            if ((line == 2) &&
                    (sign.getLine(1).toLowerCase().contains(stext))) {
                return true;
            }

            if ((line == 3) &&
                    (sign.getLine(2).toLowerCase().contains(stext))) {
                return true;
            }

            if ((line == 4) &&
                    (sign.getLine(3).toLowerCase().contains(stext))) {
                return true;
            }
        }

        return false;
    }

    public static synchronized int get_sign_type(Block block) {
        if (block == null) {
            return -1;
        }
        if (!(block.getState() instanceof Sign)) {
            return -1;
        }
        Sign sign = (Sign) block.getState();
        String key = sign.getLine(0).trim();
        if (key.contains("[Postal_Mail]"))
            return 1;
        if (key.contains("[Postal_Ship]"))
            return 2;
        if (key.contains("[Postal_Accept]"))
            return 3;
        if (key.contains("[Postal_Refuse]"))
            return 4;
        if (key.contains("[Postal_")) {
            return 0;
        }
        return -1;
    }

    public static synchronized String[] get_sign_set(Block block) {
        if (block == null) {
            return null;
        }
        if (!(block.getState() instanceof Sign)) {
            return null;
        }
        Sign sign = (Sign) block.getState();
        String[] set = new String[3];
        try {
            set[0] = sign.getLine(1).trim();
            if ((set[0] == null) || (set[0].isEmpty())) {
                set[0] = "null";
            }
            set[1] = sign.getLine(2).trim();
            if ((set[1] == null) || (set[1].isEmpty())) {
                set[1] = "null";
            }
            set[2] = sign.getLine(3).trim();
            if ((set[2] == null) || (set[2].isEmpty())) {
                set[2] = "null";
            }
        } catch (IndexOutOfBoundsException indexOutOfBoundsException) {
            return null;
        }
        return set;
    }

    public static synchronized Block sign2chest_block(Block block) {
        if (block == null) {
            return null;
        }
        Block c_block = BlockFacing.behind(block);
        if ((c_block.getState() instanceof Chest)) {
            return c_block;
        }
        return null;
    }

    public static synchronized Block LookForSignChest(Location search_location, int maxradius, String line1, String line2, String line3, String line4) {
        double y_limit = search_location.getY();

        Block b = search_location.getBlock();
        BlockFace[] faces = {BlockFace.UP, BlockFace.NORTH, BlockFace.EAST};
        BlockFace[][] orth = {{BlockFace.NORTH, BlockFace.EAST}, {BlockFace.UP, BlockFace.EAST}, {BlockFace.NORTH, BlockFace.UP}};
        for (int r = 0; r <= maxradius; r++) {
            for (int s = 0; s < 6; s++) {
                BlockFace f = faces[(s % 3)];
                BlockFace[] o = orth[(s % 3)];
                if (s >= 3) {
                    f = f.getOppositeFace();
                }
                Block c = b.getRelative(f, r);
                for (int x = -r; x <= r; x++) {
                    for (int y = -r; y <= r; y++) {
                        Block a = c.getRelative(o[0], x).getRelative(o[1], y);
                        if (ChestManip.is_chest(a.getType())) {
                            if (a.getY() > y_limit - 2.0D) {

                                if (a.getY() < y_limit + 4.0D) {

                                    if (text_lines_exists_sign_id_chest(a, line1, line2, line3, line4))
                                        return a;
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Explains a failed sign-chest lookup: every chest {@link #LookForSignChest} would consider around
     * {@code search_location}, the block in front of it, and that sign's text. Lines are for the log.
     */
    public static synchronized java.util.List<String> describe_chests_near(Location search_location, int maxradius) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (search_location == null || search_location.getWorld() == null) {
            out.add("  (no search location)");
            return out;
        }
        out.add("  Searched within " + maxradius + " blocks of " + search_location.getBlockX() + ","
                + search_location.getBlockY() + "," + search_location.getBlockZ() + " (chests at y "
                + (search_location.getBlockY() - 1) + " to " + (search_location.getBlockY() + 3) + ")");
        Block center = search_location.getBlock();
        for (int dx = -maxradius; dx <= maxradius; dx++) {
            for (int dy = -maxradius; dy <= maxradius; dy++) {
                for (int dz = -maxradius; dz <= maxradius; dz++) {
                    Block a = center.getRelative(dx, dy, dz);
                    if (!ChestManip.is_chest(a.getType())) {
                        continue;
                    }
                    String where = a.getX() + "," + a.getY() + "," + a.getZ();
                    boolean in_band = a.getY() > search_location.getY() - 2.0D && a.getY() < search_location.getY() + 4.0D;
                    BlockFace facing = BlockFacing.facing(a);
                    Block front = BlockFacing.front(a);
                    String detail;
                    if (front.getState() instanceof Sign) {
                        Sign sign = (Sign) front.getState();
                        detail = "sign " + java.util.Arrays.toString(sign.getLines());
                    } else {
                        detail = "no sign in front (" + front.getType() + " at " + front.getX() + "," + front.getY() + "," + front.getZ() + ")";
                    }
                    out.add("  chest " + where + " facing " + facing + (in_band ? "" : " [outside height band]") + ": " + detail);
                }
            }
        }
        if (out.size() == 1) {
            out.add("  no chests found");
        }
        return out;
    }

    public static synchronized boolean text_lines_exists_sign_id_chest(Block block, String line1, String line2, String line3, String line4) {
        if (!(block.getState() instanceof Chest)) {
            return false;
        }
        Block block_sign = BlockFacing.front(block);
        if (!(block_sign.getState() instanceof Sign)) {
            return false;
        }
        Sign sign = (Sign) block_sign.getState();

        if (line1 != null) {
            line1 = line1.toLowerCase().trim();
            if (!sign.getLine(0).toLowerCase().contains(line1)) {
                return false;
            }
        }
        if (line2 != null) {
            line2 = line2.toLowerCase().trim();
            if (!sign.getLine(1).toLowerCase().contains(line2)) {
                return false;
            }
        }
        if (line3 != null) {
            line3 = line3.toLowerCase().trim();
            if (!sign.getLine(2).toLowerCase().contains(line3)) {
                return false;
            }
        }
        if ((line4 != null) && !owner_line_matches(sign.getLine(3), line4)) {
            return false;
        }
        return true;
    }

    /**
     * Whether a sign's owner line names {@code owner}. Signs hold at most {@link #OWNER_LINE_MAX}
     * characters of the name (Minecraft names go to 16) and may carry colour codes; v4 compared the
     * full name against the truncated line, so owners with 16-character names never matched.
     */
    static boolean owner_line_matches(String sign_line, String owner) {
        if (sign_line == null || owner == null) {
            return false;
        }
        String wanted = owner.toLowerCase().trim();
        if (wanted.length() > OWNER_LINE_MAX) {
            wanted = wanted.substring(0, OWNER_LINE_MAX);
        }
        String line = org.bukkit.ChatColor.stripColor(sign_line);
        return line != null && line.toLowerCase().contains(wanted);
    }

    /** Longest owner name written on a mailbox sign's last line. */
    public static final int OWNER_LINE_MAX = 15;

    public static synchronized void create_sign_id_chest(Block block, String line1, String line2, String line3, String line4) {
        if (block == null) {
            return;
        }
        if (!(block.getState() instanceof Chest)) {
            return;
        }
        Block block_sign = BlockFacing.front(block);
        if (block_sign == block || !BlockFacing.place_wall_sign(block_sign, BlockFacing.facing(block))) {
            return;
        }
        if ((block_sign.getState() instanceof Sign)) {
            Sign sign = (Sign) block_sign.getState();
            sign.setLine(0, line1);
            sign.setLine(1, line2);
            sign.setLine(2, line3);
            sign.setLine(3, line4);
            sign.update();
        }
    }

    public static synchronized void edit_sign_id_chest(Block block, String line1, String line2, String line3, String line4) {
        if (block == null) {
            return;
        }
        if (!(block.getState() instanceof Chest)) {
            return;
        }
        Block block_sign = BlockFacing.front(block);
        if ((block_sign.getState() instanceof Sign)) {
            Sign sign = (Sign) block_sign.getState();
            if (line1 != null) {
                sign.setLine(0, line1);
            }
            if (line2 != null) {
                sign.setLine(1, line2);
            }
            if (line3 != null) {
                sign.setLine(2, line3);
            }
            if (line4 != null) {
                if (line4.length() > 15) {
                    line4 = line4.substring(0, 15);
                }
                sign.setLine(3, line4);
            }
            sign.update();
        }
    }

    public static synchronized void remove_sign_id_chest(Block block) {
        if (block == null) {
            return;
        }
        if (!(block.getState() instanceof Chest)) {
            return;
        }
        Block block_sign = BlockFacing.front(block);
        if (block_sign != block && BlockFacing.is_wall_sign(block_sign)) {
            block_sign.setType(Material.AIR);
        }
    }

    public static synchronized boolean exists_sign_id_chest(Block block) {
        if (block == null) {
            return false;
        }
        if (!(block.getState() instanceof Chest)) {
            return false;
        }
        Block block_sign = BlockFacing.front(block);
        return BlockFacing.is_wall_sign(block_sign);
    }

    public static synchronized boolean text_exists_sign_id_chest(Block block, String stext, int line, boolean global) {
        if (block == null) {
            return false;
        }
        stext = stext.toLowerCase().trim();
        if (!(block.getState() instanceof Chest)) {
            return false;
        }
        Block block_sign = BlockFacing.front(block);
        if ((block_sign.getState() instanceof Sign)) {
            Sign sign = (Sign) block_sign.getState();
            if (((global) || (line == 1)) &&
                    (sign.getLine(0).toLowerCase().contains(stext))) {
                return true;
            }

            if (((global) || (line == 2)) &&
                    (sign.getLine(1).toLowerCase().contains(stext))) {
                return true;
            }

            if (((global) || (line == 3)) &&
                    (sign.getLine(2).toLowerCase().contains(stext))) {
                return true;
            }

            if (((global) || (line == 4)) &&
                    (sign.getLine(3).toLowerCase().contains(stext))) {
                return true;
            }
        }

        return false;
    }
}
