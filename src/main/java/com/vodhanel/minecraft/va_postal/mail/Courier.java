package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.BlockFacing;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.navigation.NpcLook;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;

/**
 * The courier who collects a packed parcel's chest at its first pickup (persistent-state phase P2). The parcel's
 * items are already in its record, so this is purely for show: if a player is near, a postal courier walks up,
 * picks the chest up, walks off with it and is gone; otherwise the chest is simply removed. Whatever goes wrong
 * (no Citizens, a stuck NPC, an unloaded chunk, a shutdown), the chest is still removed and the NPC never kept.
 */
public final class Courier {
    /** How near a player must be for the courier to come in person. */
    static final double AUDIENCE = 40.0D;
    /** At most this many couriers at once; beyond that, chests are removed quietly. */
    static final int MAX_ACTIVE = 3;
    private static final String NAME = "&9Postal Courier";
    private static final Set<Scene> active = new HashSet<>();

    private Courier() {
    }

    /** Collects the parcel chest at {@code chest}: with a courier if someone can see, otherwise at once. */
    public static void collect(Block chest) {
        if (!(chest.getState() instanceof Chest)) {
            return;
        }
        if (active.size() >= MAX_ACTIVE || !audience(chest.getLocation()) || !citizens()) {
            remove(chest);
            return;
        }
        try {
            Scene scene = new Scene(chest);
            active.add(scene);
            scene.runTaskTimer(VA_postal.plugin, 1L, 5L);
        } catch (RuntimeException | LinkageError e) {
            Util.cinform("[Postal] Courier unavailable (" + e.getMessage() + "); removing the parcel chest directly.");
            remove(chest);
        }
    }

    /** Ends every scene now (shutdown): chests are removed, couriers destroyed. */
    public static void shutdown() {
        for (Scene scene : new HashSet<>(active)) {
            scene.finish(true);
        }
        active.clear();
    }

    /** Removes a parcel chest and its sign; anything a hopper pushed in since packing is dropped, not lost. */
    static void remove(Block chest) {
        if (chest.getState() instanceof Chest) {
            Location drop = chest.getLocation().add(0.5D, 0.5D, 0.5D);
            for (ItemStack item : ((Chest) chest.getState()).getInventory().getContents()) {
                if (item != null && !item.getType().isAir()) {
                    chest.getWorld().dropItemNaturally(drop, item);
                }
            }
            ((Chest) chest.getState()).getInventory().clear();
            SignManip.remove_sign_id_chest(chest);
            chest.setType(Material.AIR);
        }
    }

    private static boolean audience(Location at) {
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= AUDIENCE * AUDIENCE) {
                return true;
            }
        }
        return false;
    }

    private static boolean citizens() {
        try {
            return CitizensAPI.hasImplementation();
        } catch (LinkageError e) {
            return false;
        }
    }

    /** A place to stand near {@code from}, about {@code distance} blocks away, on solid ground with headroom. */
    public static Location stand_near(Location from, int distance) {
        BlockFace[] around = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST,
                BlockFace.NORTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST, BlockFace.NORTH_WEST};
        for (int d = distance; d >= 2; d--) {
            for (BlockFace f : around) {
                Location at = standable(from.clone().add(f.getModX() * d, 0, f.getModZ() * d));
                if (at != null) {
                    return at;
                }
            }
        }
        return null;
    }

    private static Location standable(Location at) {
        Block base = at.getBlock();
        for (int dy = 3; dy >= -3; dy--) {
            Block feet = base.getRelative(0, dy, 0);
            if (feet.getType().isAir() && feet.getRelative(BlockFace.UP).getType().isAir()
                    && feet.getRelative(BlockFace.DOWN).getType().isSolid()) {
                return feet.getLocation().add(0.5D, 0.0D, 0.5D);
            }
        }
        return null;
    }

    /** Walk in, pick the chest up, walk off, vanish. Driven every 5 ticks. */
    private static final class Scene extends BukkitRunnable {
        private final Block chest;
        private final Location chest_center;
        private final Location start;
        private final Location stand;
        private final NPC npc;
        private int phase;
        private int ticks;
        private boolean done;

        Scene(Block chest) {
            this.chest = chest;
            this.chest_center = chest.getLocation().add(0.5D, 0.5D, 0.5D);
            // Stand in front of the chest's sign if there's room, else beside the chest.
            BlockFace facing = BlockFacing.facing(chest);
            Location in_front = facing == null ? null
                    : standable(chest.getRelative(facing).getRelative(facing).getLocation());
            Location beside = in_front != null ? in_front : stand_near(chest.getLocation(), 2);
            Location from = stand_near(chest.getLocation(), 9);
            if (beside == null) {
                throw new IllegalStateException("no room beside the chest");
            }
            this.stand = beside;
            this.start = from != null ? from : beside;
            this.npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, NAME);
            npc.data().set(NPC.Metadata.SHOULD_SAVE, false);
            NpcLook.skin(npc, true);
            npc.spawn(start);
            NpcLook.uniform(npc, true);
            npc.getNavigator().getDefaultParameters().speedModifier(0.9F);
            npc.getNavigator().setTarget(stand);
        }

        @Override
        public void run() {
            try {
                ticks += 5;
                if (!npc.isSpawned() || !chest.getWorld().isChunkLoaded(chest.getX() >> 4, chest.getZ() >> 4)) {
                    finish(true);
                    return;
                }
                switch (phase) {
                    case 0: // walking to the chest
                        if (npc.getEntity().getLocation().distanceSquared(stand) < 2.5D || ticks > 240) {
                            if (npc.getEntity().getLocation().distanceSquared(stand) >= 2.5D) {
                                npc.teleport(stand, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                            }
                            npc.getNavigator().cancelNavigation();
                            npc.faceLocation(chest_center);
                            phase = 1;
                            ticks = 0;
                        }
                        break;
                    case 1: // a moment at the chest, then pick it up
                        npc.faceLocation(chest_center);
                        if (ticks >= 15) {
                            if (npc.getEntity() instanceof LivingEntity) {
                                ((LivingEntity) npc.getEntity()).swingMainHand();
                            }
                            remove(chest);
                            chest.getWorld().playSound(chest_center, org.bukkit.Sound.BLOCK_CHEST_CLOSE, 0.6F, 1.0F);
                            NpcLook.hold(npc, new ItemStack(Material.CHEST));
                            phase = 2;
                            ticks = 0;
                        }
                        break;
                    case 2: // turn and walk away
                        if (ticks >= 20) {
                            npc.getNavigator().setTarget(start);
                            phase = 3;
                            ticks = 0;
                        }
                        break;
                    default: // gone, once far enough or after a while
                        if (npc.getEntity().getLocation().distanceSquared(start) < 2.5D || ticks > 200) {
                            finish(false);
                        }
                }
            } catch (RuntimeException e) {
                finish(true);
            }
        }

        void finish(boolean abrupt) {
            if (done) {
                return;
            }
            done = true;
            remove(chest); // whatever happened, the chest goes
            try {
                if (npc.isSpawned() && !abrupt) {
                    Location at = npc.getEntity().getLocation().add(0.0D, 1.0D, 0.0D);
                    at.getWorld().spawnParticle(Particle.CLOUD, at, 20, 0.3D, 0.6D, 0.3D, 0.02D);
                }
                npc.destroy();
            } catch (RuntimeException ignored) {
            }
            active.remove(this);
            try {
                cancel();
            } catch (IllegalStateException ignored) {
            }
        }
    }
}
