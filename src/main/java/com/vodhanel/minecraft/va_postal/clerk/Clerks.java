package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.BlockFacing;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.mail.ChestManip;
import com.vodhanel.minecraft.va_postal.mail.Courier;
import com.vodhanel.minecraft.va_postal.navigation.NpcLook;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.LookClose;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The local post offices' clerks (docs/design/deeds.md §2): one NPC per office on this server, standing at its post
 * while the mail runs. Spawned when the dispatcher starts, put back by {@link #tend()} every few seconds if anything
 * removed or moved them, and gone when it stops. Protected (invulnerable, not pushed or leashed) and never saved by
 * Citizens. Right-clicking one opens the office's counter ({@link CounterMenu}).
 */
public final class Clerks {
    /** How often the clerks are looked after, in ticks. */
    static final long TEND_TICKS = 100L;
    /** Further than this from their post, a clerk is put back. */
    static final double STRAY = 1.5D;
    /** Players this near a clerk are greeted. */
    static final double GREET_RANGE = 4.0D;
    /** A player is greeted again after this long. */
    static final long GREET_AGAIN_MS = 10L * 60L * 1000L;
    /** Spoken lines carry this far. */
    static final double EARSHOT = 12.0D;

    private static final Map<String, NPC> clerks = new LinkedHashMap<>();
    private static final Map<UUID, Long> greeted = new HashMap<>();
    private static BukkitTask task;

    private Clerks() {
    }

    /** {@code Settings.Clerks.Local}: false leaves offices unstaffed; the counter then opens from the office sign. */
    public static boolean enabled() {
        return GetConfig.bool_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.clerks.local"), true);
    }

    /** Staffs every office (the dispatcher has started). */
    public static synchronized void start() {
        stop();
        if (!enabled() || !citizens()) {
            return;
        }
        task = VA_postal.plugin.getServer().getScheduler().runTaskTimer(VA_postal.plugin, (Runnable) Clerks::tend, 20L, TEND_TICKS);
    }

    /** Sends every clerk home (the dispatcher has stopped, or the plugin is shutting down). */
    public static synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (NPC npc : clerks.values()) {
            try {
                npc.destroy();
            } catch (RuntimeException ignored) {
            }
        }
        clerks.clear();
        greeted.clear();
    }

    /** Spawns missing clerks, puts strays back, dismisses clerks of offices that are gone, and greets visitors. */
    static synchronized void tend() {
        List<String> offices = offices();
        clerks.entrySet().removeIf(e -> {
            if (offices.contains(e.getKey())) {
                return false;
            }
            e.getValue().destroy();
            return true;
        });
        for (String office : offices) {
            try {
                tend(office);
            } catch (RuntimeException e) {
                Util.dinform("[Postal] Clerk of " + office + ": " + e.getMessage());
            }
        }
        long now = System.currentTimeMillis();
        greeted.values().removeIf(t -> now - t > GREET_AGAIN_MS);
    }

    private static void tend(String office) {
        Location post = post(office);
        if (post == null || !post.getWorld().isChunkLoaded(post.getBlockX() >> 4, post.getBlockZ() >> 4)) {
            return; // Citizens despawns NPCs in unloaded chunks and brings them back with the chunk
        }
        NPC npc = clerks.get(office);
        if (npc != null && CitizensAPI.getNPCRegistry().getById(npc.getId()) != npc) {
            npc = null; // removed behind our back (/npc remove): hire another
        }
        if (npc == null) {
            npc = create(office);
            clerks.put(office, npc);
        }
        if (!npc.isSpawned()) {
            if (npc.spawn(post)) {
                dress(npc);
            }
            return;
        }
        Location at = npc.getEntity().getLocation();
        if (at.getWorld() != post.getWorld() || at.distanceSquared(post) > STRAY * STRAY) {
            npc.teleport(post, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        greet(office, npc);
    }

    private static NPC create(String office) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, Text.color(name(office)));
        npc.data().set(NPC.Metadata.SHOULD_SAVE, false);
        npc.setProtected(true);
        npc.data().set(NPC.Metadata.COLLIDABLE, false);
        npc.data().set(NPC.Metadata.LEASH_PROTECTED, true);
        npc.data().set(NPC.Metadata.FLUID_PUSHABLE, false);
        npc.data().set(NPC.Metadata.KNOCKBACK, false);
        NpcLook.skin(npc, "central");
        LookClose look = npc.getOrAddTrait(LookClose.class);
        look.lookClose(true);
        look.setRange(GREET_RANGE * 2.0D);
        look.setRealisticLooking(true);
        return npc;
    }

    /** Puts on the uniform once spawned (Citizens keeps equipment on the NPC). */
    static void dress(NPC npc) {
        NpcLook.uniform(npc, "central", "true".equalsIgnoreCase(config("settings.uniform.enabled", "false").trim()));
    }

    private static void greet(String office, NPC npc) {
        Location at = npc.getEntity().getLocation();
        long now = System.currentTimeMillis();
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= GREET_RANGE * GREET_RANGE && !greeted.containsKey(p.getUniqueId())) {
                greeted.put(p.getUniqueId(), now);
                say(office, p, fill(config("settings.clerks.greeting",
                        "Good day, {player}! Welcome to the {office} Post Office. Right-click me for the counter."), office, p));
            }
        }
    }

    /** The clerk of {@code office} says {@code line} to {@code player}. */
    public static void say(String office, Player player, String line) {
        player.sendMessage(Text.color("&7<" + name(office) + "&7> &f" + line));
    }

    /** The office whose clerk {@code npc} is, or null. */
    public static synchronized String office_of(NPC npc) {
        for (Map.Entry<String, NPC> e : clerks.entrySet()) {
            if (e.getValue() == npc) {
                return e.getKey();
            }
        }
        return null;
    }

    /** The clerk of {@code office}, or null. */
    public static synchronized NPC clerk(String office) {
        return clerks.get(office);
    }

    /** This server's local post offices, lower case. */
    static List<String> offices() {
        List<String> out = new ArrayList<>();
        ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("postoffice.local"));
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                String loc = C_Postoffice.get_local_po_location_by_name(key);
                if (loc != null && !"null".equals(loc)) {
                    out.add(key.toLowerCase(Locale.ROOT).trim());
                }
            }
        }
        return out;
    }

    /** Where the clerk of {@code office} stands: as placed with /postal clerk, else beside the office chest. */
    public static Location post(String office) {
        Location placed = placed(office);
        if (placed != null) {
            return placed;
        }
        // An office's Location is where the postman stands, two blocks in front of its chest.
        Location spot = Util.str2location(C_Postoffice.get_local_po_location_by_name(office));
        if (spot == null) {
            return null;
        }
        Block chest = office_chest(office);
        BlockFace facing = chest == null ? null : BlockFacing.facing(chest);
        Location at = null;
        if (facing != null) {
            // At the chest's side, as at a counter, looking out the way the chest does.
            for (BlockFace side : new BlockFace[]{rotate(facing), rotate(facing).getOppositeFace()}) {
                at = standable(chest.getRelative(side));
                if (at != null) {
                    break;
                }
            }
        }
        if (at == null) {
            at = Courier.stand_near(spot, 2);
        }
        if (at != null && facing != null) {
            at.setYaw(yaw(facing));
        }
        return at;
    }

    /** The office's chest: the chest nearest the spot its Location names. */
    static Block office_chest(String office) {
        Location spot = Util.str2location(C_Postoffice.get_local_po_location_by_name(office));
        return spot == null ? null : ChestManip.getNearestGenericChest(spot.getBlock(), 3);
    }

    private static BlockFace rotate(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    private static Location standable(Block feet) {
        for (int dy : new int[]{0, 1, -1, -2}) {
            Block b = feet.getRelative(0, dy, 0);
            if (b.isPassable() && b.getRelative(BlockFace.UP).isPassable() && b.getRelative(BlockFace.DOWN).getType().isSolid()) {
                return b.getLocation().add(0.5D, 0.0D, 0.5D);
            }
        }
        return null;
    }

    static float yaw(BlockFace face) {
        return switch (face) {
            case SOUTH -> 0.0F;
            case WEST -> 90.0F;
            case NORTH -> 180.0F;
            default -> -90.0F;
        };
    }

    /** The post set with /postal clerk: {@code world,x,y,z,yaw}. */
    private static Location placed(String office) {
        String s = VA_postal.plugin.getConfig().getString(GetConfig.path_format("postoffice.local." + office + ".clerk"));
        Location at = s == null ? null : Util.str2location(s);
        if (at == null) {
            return null;
        }
        String[] parts = s.split(",");
        at.add(0.5D, 0.0D, 0.5D);
        if (parts.length > 4) {
            try {
                at.setYaw(Float.parseFloat(parts[4].trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return at;
    }

    /** Stations the clerk of {@code office} at {@code at} (null: back beside the office chest). */
    public static synchronized void place(String office, Location at) {
        String path = GetConfig.path_format("postoffice.local." + office + ".clerk");
        VA_postal.plugin.getConfig().set(path, at == null ? null : Util.location2str(at) + "," + Math.round(at.getYaw()));
        VA_postal.plugin.saveConfig();
        NPC npc = clerks.get(office);
        Location post = post(office);
        if (npc != null && npc.isSpawned() && post != null) {
            npc.teleport(post, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    /** One line per office for /postal clerk list. */
    public static synchronized List<String> describe() {
        List<String> out = new ArrayList<>();
        for (String office : offices()) {
            Location post = post(office);
            NPC npc = clerks.get(office);
            String state = !enabled() ? "clerks are off" : task == null ? "off duty (the mail isn't running)"
                    : npc != null && npc.isSpawned() ? "on duty" : "not here yet";
            out.add("&f" + DeedDocument.display(office) + "&7: " + Text.strip(Text.color(name(office))) + ", "
                    + (post == null ? "no place to stand" : "at " + post.getBlockX() + " " + post.getBlockY() + " " + post.getBlockZ())
                    + (placed(office) == null ? " (beside the office chest)" : "") + ", " + state
                    + "; addresses " + price_text(office) + (has_price(office) ? " (office price)" : ""));
        }
        return out;
    }

    /** The clerk's name: {@code Settings.Clerks.Name}, "{office}" filled in. */
    public static String name(String office) {
        return config("settings.clerks.name", "&6Postmaster of {office}").replace("{office}", DeedDocument.display(office));
    }

    static String fill(String line, String office, Player player) {
        return line.replace("{office}", DeedDocument.display(office)).replace("{player}", player.getName());
    }

    /** What a new address at {@code office} costs: the office's own price, else the server's. */
    public static double price(String office) {
        Double own = own_price(office);
        return own != null ? own : C_Economy.addr_purchase_price();
    }

    public static boolean has_price(String office) {
        return own_price(office) != null;
    }

    static String price_text(String office) {
        double p = price(office);
        return !VA_postal.economy_configured || p <= 0.0D ? "free"
                : com.vodhanel.minecraft.va_postal.common.P_Economy.ef(p);
    }

    private static Double own_price(String office) {
        String s = VA_postal.plugin.getConfig().getString(GetConfig.path_format("postoffice.local." + office + ".address_price"));
        return parse_price(s);
    }

    /** A price as typed: a finite, non-negative number with at most two decimals' worth, or null. */
    static Double parse_price(String s) {
        if (s == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(s.trim().replace(",", ""));
            return Double.isFinite(v) && v >= 0.0D && v <= 1.0E12D ? Math.round(v * 100.0D) / 100.0D : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sets (or, with null, clears) the office's own address price. */
    public static void set_price(String office, Double price) {
        VA_postal.plugin.getConfig().set(GetConfig.path_format("postoffice.local." + office + ".address_price"),
                price == null ? null : String.format(Locale.ROOT, "%.2f", price));
        VA_postal.plugin.saveConfig();
    }

    static String config(String path, String fallback) {
        String v = VA_postal.plugin.getConfig().getString(GetConfig.path_format(path));
        return v == null ? fallback : v;
    }

    private static boolean citizens() {
        try {
            return CitizensAPI.hasImplementation();
        } catch (LinkageError e) {
            return false;
        }
    }
}
