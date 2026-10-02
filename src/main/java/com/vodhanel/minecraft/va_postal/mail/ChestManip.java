package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class ChestManip {
    VA_postal plugin;

    public ChestManip(VA_postal instance) {
        this.plugin = instance;
    }

    public static synchronized Block block2postal_block(Block block) {
        if (block == null) {
            return null;
        }

        int chest_type = postal_chest_type(block);
        if (chest_type == -1) {
            return null;
        }

        if (chest_type == 1) {
            return block;
        }

        if (chest2sign_block(block) != null) {
            return block;
        }

        Location test_loc = block.getLocation().clone();
        test_loc.add(1.0, 0.0, 0.0);
        if (chest2sign_block(test_loc.getBlock()) != null) {
            return test_loc.getBlock();
        }

        test_loc = block.getLocation().clone();
        test_loc.subtract(1.0, 0.0, 0.0);
        if (chest2sign_block(test_loc.getBlock()) != null) {
            return test_loc.getBlock();
        }

        test_loc = block.getLocation().clone();
        test_loc.add(0.0, 0.0, 1.0);
        if (chest2sign_block(test_loc.getBlock()) != null) {
            return test_loc.getBlock();
        }

        test_loc = block.getLocation().clone();
        test_loc.subtract(0.0, 0.0, 1.0);
        return chest2sign_block(test_loc.getBlock()) != null ? test_loc.getBlock() : null;
    }

    public static synchronized Block chest2sign_block(Block block) {
        if (block == null) {
            return null;
        }

        int dir = block.getData();
        Location chest_front = block.getLocation();
        if (dir == 2) {
            chest_front.subtract(0.0, 0.0, 1.0);
        } else if (dir == 3) {
            chest_front.add(0.0, 0.0, 1.0);
        } else if (dir == 4) {
            chest_front.subtract(1.0, 0.0, 0.0);
        } else if (dir == 5) {
            chest_front.add(1.0, 0.0, 0.0);
        }

        World w = chest_front.getWorld();
        Block s_block = w.getBlockAt(chest_front);
        return s_block.getState() instanceof Sign ? s_block : null;
    }

    public static synchronized boolean is_this_a_postal_chest(Block block) {
        if (block == null) {
            return false;
        } else if (!(block.getState() instanceof Chest)) {
            return false;
        } else {
            return BookManip.valid_postal_log(block, null, null) ? true : block2postal_block(block) != null;
        }
    }

    public static synchronized int postal_chest_type(Block block) {
        if (block == null) {
            return -1;
        }

        if (block.getState() instanceof Chest) {
            Inventory inventory = BookManip.postal_inventory(block);
            if (inventory == null) {
                return -1;
            } else {
                return inventory.getSize() == 27 ? 1 : 2;
            }
        } else {
            return -1;
        }
    }

    public static synchronized Block at_chest(Player player) {
        Location location = player.getLocation();
        return getNearestPackageChest(location, 1, player);
    }

    public static synchronized Block get_parcel_chest(Player player) {
        Location location = player.getLocation();
        return getNearestPackageChest(location, 1, player);
    }

    public static synchronized Block parcel_place_chest_accept(Player player) {
        byte dir = 0;
        Location location = player.getLocation();
        double rotation = (player.getLocation().getYaw() - 90.0F) % 360.0F;
        if (rotation < 0.0) {
            rotation += 360.0;
        }

        if (0.0 <= rotation && rotation < 22.5) {
            location.subtract(1.0, 0.0, 0.0);
            dir = 5;
        } else if (22.5 <= rotation && rotation < 67.5) {
            location.subtract(1.0, 0.0, 1.0);
            dir = 5;
        } else if (67.5 <= rotation && rotation < 112.5) {
            location.subtract(0.0, 0.0, 1.0);
            dir = 3;
        } else if (112.5 <= rotation && rotation < 157.5) {
            location.subtract(0.0, 0.0, 1.0);
            location.add(1.0, 0.0, 0.0);
            dir = 3;
        } else if (157.5 <= rotation && rotation < 202.5) {
            location.add(1.0, 0.0, 0.0);
            dir = 4;
        } else if (202.5 <= rotation && rotation < 247.5) {
            location.add(1.0, 0.0, 1.0);
            dir = 4;
        } else if (247.5 <= rotation && rotation < 292.5) {
            location.add(0.0, 0.0, 1.0);
            dir = 2;
        } else if (292.5 <= rotation && rotation < 337.5) {
            location.add(0.0, 0.0, 1.0);
            location.subtract(1.0, 0.0, 0.0);
            dir = 2;
        } else if (337.5 <= rotation && rotation < 360.0) {
            location.subtract(1.0, 0.0, 0.0);
            dir = 5;
        }

        World w = location.getWorld();
        Block test_block = w.getBlockAt(location);
        if (test_block == null) {
            return null;
        }

        int stack_height = 0;

        while (test_block.getTypeId() != 0) {
            location.add(0.0, 1.0, 0.0);
            test_block = w.getBlockAt(location);
            if (++stack_height > 4) {
                break;
            }
        }

        if (stack_height <= 4) {
            while (test_block.getTypeId() == 0) {
                location.subtract(0.0, 1.0, 0.0);
                test_block = w.getBlockAt(location);
            }

            location.add(0.0, 1.0, 0.0);
            test_block = w.getBlockAt(location);
            if (test_block.getTypeId() == 0) {
                try {
                    test_block.setTypeId(54);
                    test_block.setData(dir);
                    return test_block;
                } catch (Exception e) {
                    return null;
                }
            }
        }

        return null;
    }

    public static synchronized Block parcel_place_chest_refuse(ItemStack ind_item) {
        Book book = null;

        try {
            book = new Book(ind_item);
        } catch (Exception e) {
            book = null;
            return null;
        }

        String[] spage = book.getPages();
        String[] parts = spage[1].split("\n");
        String sworld = parts[0].substring(2).trim();
        String scoords = parts[1].trim();
        String[] parts_c = scoords.split(",");
        String X = "";
        String Y = "";
        String Z = "";
        String sdir = "";
        byte dir = 0;

        try {
            X = parts_c[0].trim();
            Y = parts_c[1].trim();
            Z = parts_c[2].trim();
            sdir = parts_c[3].trim();
            dir = Byte.parseByte(sdir);
        } catch (Exception e) {
            book = null;
            return null;
        }

        String slocation = sworld + "," + X + "," + Y + "," + Z;
        Location location = Util.str2location(slocation);
        World w = location.getWorld();
        Block test_block = w.getBlockAt(location);
        if (test_block == null) {
            return null;
        }

        int stack_height = 0;

        while (test_block.getTypeId() != 0) {
            location.add(0.0, 1.0, 0.0);
            test_block = w.getBlockAt(location);
            if (++stack_height > 4) {
                break;
            }
        }

        if (stack_height <= 4) {
            while (test_block.getTypeId() == 0) {
                location.subtract(0.0, 1.0, 0.0);
                test_block = w.getBlockAt(location);
            }

            location.add(0.0, 1.0, 0.0);
            test_block = w.getBlockAt(location);
            if (test_block.getTypeId() == 0) {
                try {
                    test_block.setTypeId(54);
                    test_block.setData(dir);
                    book = null;
                    return test_block;
                } catch (Exception e) {
                    return null;
                }
            }
        }

        book = null;
        return null;
    }

    public static synchronized boolean parcel_remove_origen_chest(ItemStack ind_item) {
        Book book = null;

        try {
            book = new Book(ind_item);
        } catch (Exception e) {
            book = null;
            return false;
        }

        String[] spage = book.getPages();
        String[] parts = spage[1].split("\n");
        String sworld = parts[0].substring(2).trim();
        String scoords = parts[1].trim();
        String[] parts_c = scoords.split(",");
        String X = "";
        String Y = "";
        String Z = "";

        try {
            X = parts_c[0].trim();
            Y = parts_c[1].trim();
            Z = parts_c[2].trim();
        } catch (Exception e) {
            book = null;
            return false;
        }

        book = null;
        String slocation = sworld + "," + X + "," + Y + "," + Z;
        Location location = Util.str2location(slocation);
        World w = location.getWorld();
        Block block = w.getBlockAt(location);
        if (block == null) {
            return false;
        }

        if (!(block.getState() instanceof Chest)) {
            return false;
        }

        Chest chest = (Chest)block.getState();
        chest.getInventory().clear();
        SignManip.remove_sign_id_chest(block);

        try {
            block.setTypeId(0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized boolean add_to_central_chest(final ItemStack book_item) {
        if (book_item == null) {
            return false;
        }

        Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(VA_postal.plugin, new Runnable() {
            @Override
            public void run() {
                VA_postal.central_po_inventory.addItem(new ItemStack[]{book_item});
            }
        }, 12L);
        return true;
    }

    public static synchronized boolean set_central_chest_inv() {
        VA_postal.central_po_slocation = Util.put_point_on_ground(C_Postoffice.get_central_po_location(), false);
        if (VA_postal.cstalk) {
            Util.cinform("\u001b[0;36mSearching for Central post office chest....");
            Util.cinform("\u001b[0;36m  Starting at:  " + VA_postal.central_po_slocation);
        }

        ID_Mail.SetPostoffice_Chest_nTP_point(1000);
        if ("null".equals(VA_postal.central_schest_location)) {
            Util.cinform("\u001b[1;31m[set_central_chest_inv] unable to locate chest");
            return false;
        } else {
            Util.dinform("\u001b[0;36m[Central PO] chest location: " + VA_postal.central_schest_location);
            Location mailbox_loc = Util.str2location(VA_postal.central_schest_location);
            Chest chest = (Chest)mailbox_loc.getBlock().getState();
            if (chest != null && is_chest(chest.getTypeId())) {
                VA_postal.central_po_inventory = chest.getInventory();
                return true;
            } else {
                Util.cinform("\u001b[1;31m[set_central_chest_inv] unable set inventory");
                VA_postal.central_po_inventory = null;
                return false;
            }
        }
    }

    public static synchronized ItemStack central_create_log() {
        String title = "§c[Postal Log]";
        String author = "Central";
        String slocation = VA_postal.central_schest_location;
        String[] parts = slocation.split(",");
        String sworld = parts[0];
        String slocation_mod = parts[1] + "," + parts[2] + "," + parts[3];
        String[] pages = new String[1];
        String p1 = sworld + "\n";
        p1 = p1 + slocation_mod + "\n";
        p1 = p1 + "Postal_Central\n";
        p1 = p1 + "Server\n";
        p1 = p1 + "§2" + "[" + Util.stime_stamp() + "]\n";
        pages[0] = p1;
        Book book = new Book(title, author, pages);
        return book.generateItemStack();
    }

    public static synchronized String chest_front(Block block) {
        if (block == null) {
            return "null";
        }

        int dir = block.getData();
        Location chest_front = block.getLocation();
        if (dir == 2) {
            chest_front.subtract(0.0, 0.0, 1.0);
        } else if (dir == 3) {
            chest_front.add(0.0, 0.0, 1.0);
        } else if (dir == 4) {
            chest_front.subtract(1.0, 0.0, 0.0);
        } else if (dir == 5) {
            chest_front.add(1.0, 0.0, 0.0);
        }

        return Util.location2str(chest_front);
    }

    public static synchronized Block getNearestPackageChest(Location location, int maxradius, Player player) {
        Block b = location.getBlock();
        BlockFace[] faces = new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.EAST};
        BlockFace[][] orth = new BlockFace[][]{{BlockFace.NORTH, BlockFace.EAST}, {BlockFace.UP, BlockFace.EAST}, {BlockFace.NORTH, BlockFace.UP}};

        for (int r = 0; r <= maxradius; r++) {
            for (int s = 0; s < 6; s++) {
                BlockFace f = faces[s % 3];
                BlockFace[] o = orth[s % 3];
                if (s >= 3) {
                    f = f.getOppositeFace();
                }

                Block c = b.getRelative(f, r);

                for (int x = -r; x <= r; x++) {
                    for (int y = -r; y <= r; y++) {
                        Block a = c.getRelative(o[0], x).getRelative(o[1], y);
                        if (is_chest(a.getTypeId())) {
                            if (!is_this_a_postal_chest(a)) {
                                return a;
                            }

                            if (player != null) {
                                Util.pinform(player, "&c&oYou may not use a Postal chest.");
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    public static synchronized Block getNearestGenericChest_to_player(Player player, int maxradius) {
        Location location = player.getLocation();
        Block b = location.getBlock();
        BlockFace[] faces = new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.EAST};
        BlockFace[][] orth = new BlockFace[][]{{BlockFace.NORTH, BlockFace.EAST}, {BlockFace.UP, BlockFace.EAST}, {BlockFace.NORTH, BlockFace.UP}};

        for (int r = 0; r <= maxradius; r++) {
            for (int s = 0; s < 6; s++) {
                BlockFace f = faces[s % 3];
                BlockFace[] o = orth[s % 3];
                if (s >= 3) {
                    f = f.getOppositeFace();
                }

                Block c = b.getRelative(f, r);

                for (int x = -r; x <= r; x++) {
                    for (int y = -r; y <= r; y++) {
                        Block a = c.getRelative(o[0], x).getRelative(o[1], y);
                        if (is_chest(a.getTypeId())) {
                            return a;
                        }
                    }
                }
            }
        }

        return null;
    }

    public static synchronized Block getNearestGenericChest(Block b, int maxradius) {
        BlockFace[] faces = new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.EAST};
        BlockFace[][] orth = new BlockFace[][]{{BlockFace.NORTH, BlockFace.EAST}, {BlockFace.UP, BlockFace.EAST}, {BlockFace.NORTH, BlockFace.UP}};

        for (int r = 0; r < maxradius; r++) {
            for (int s = 0; s < 6; s++) {
                BlockFace f = faces[s % 3];
                BlockFace[] o = orth[s % 3];
                if (s >= 3) {
                    f = f.getOppositeFace();
                }

                Block c = b.getRelative(f, r);

                for (int x = -r; x <= r; x++) {
                    for (int y = -r; y <= r; y++) {
                        Block a = c.getRelative(o[0], x).getRelative(o[1], y);
                        if (is_chest(a.getTypeId())) {
                            return a;
                        }
                    }
                }
            }
        }

        return null;
    }

    public static synchronized boolean does_chest_have_sign(Block b) {
        BlockFace[] faces = new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.EAST};
        BlockFace[][] orth = new BlockFace[][]{{BlockFace.NORTH, BlockFace.EAST}, {BlockFace.UP, BlockFace.EAST}, {BlockFace.NORTH, BlockFace.UP}};

        for (int s = 0; s < 6; s++) {
            BlockFace f = faces[s % 3];
            BlockFace[] o = orth[s % 3];
            if (s >= 3) {
                f = f.getOppositeFace();
            }

            Block c = b.getRelative(f, 1);

            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    Block a = c.getRelative(o[0], x).getRelative(o[1], y);
                    if (a.getTypeId() == 68) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static synchronized boolean is_chest(int id_type) {
        return id_type == 54 || id_type == 146;
    }

    public static synchronized boolean ok_to_use_chest(Block block, boolean check_is_postal) {
        if (block == null) {
            return false;
        }

        if (!(block.getState() instanceof Chest)) {
            return false;
        }

        if (check_is_postal && is_this_a_postal_chest(block)) {
            return true;
        }

        if (!does_chest_have_sign(block)) {
            return true;
        }

        Chest chest = (Chest)block.getState();
        Inventory inventory = chest.getInventory();
        if (inventory.getSize() > 27) {
            Location test_loc = block.getLocation().clone();
            test_loc.add(1.0, 0.0, 0.0);
            if (is_chest(test_loc.getBlock().getTypeId()) && !does_chest_have_sign(test_loc.getBlock())) {
                return true;
            }

            test_loc = block.getLocation().clone();
            test_loc.subtract(1.0, 0.0, 0.0);
            if (is_chest(test_loc.getBlock().getTypeId()) && !does_chest_have_sign(test_loc.getBlock())) {
                return true;
            }

            test_loc = block.getLocation().clone();
            test_loc.add(0.0, 0.0, 1.0);
            if (is_chest(test_loc.getBlock().getTypeId()) && !does_chest_have_sign(test_loc.getBlock())) {
                return true;
            }

            test_loc = block.getLocation().clone();
            test_loc.subtract(0.0, 0.0, 1.0);
            if (is_chest(test_loc.getBlock().getTypeId()) && !does_chest_have_sign(test_loc.getBlock())) {
                return true;
            }
        }

        return false;
    }
}
