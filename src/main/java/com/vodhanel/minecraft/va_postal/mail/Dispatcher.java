package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.BlockFacing;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.navigation.NpcLook;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Lidded;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.function.Supplier;

/**
 * The dispatcher: the character who carries the mail between this server's Central and the mail ship (persistent-state
 * §17). At a departure they walk in from a few dozen blocks away, open Central's chest, take the outbound letters
 * in a mailbag, say so, and walk off; the bell rings as the ship leaves. At an arrival they walk in carrying the
 * bag, ring the bell, leave the letters in the chest and walk off. (Not to be confused with {@code VA_Dispatcher},
 * which schedules the postmen's rounds: this is a character players see.)
 * <p>
 * The transfer itself (the letters' records and books) happens when the dispatcher reaches the chest, through the
 * {@code transfer} callback. They are only ever late, never in the way: with nobody near Central to see it,
 * without Citizens, or if anything goes wrong (a stuck walk past its time limit, an unloaded chunk, a shutdown),
 * the transfer runs at once. The NPC is never saved by Citizens.
 */
public final class Dispatcher {
    /** How near Central a player must be for the dispatcher to come in person. */
    static final double AUDIENCE = 48.0D;
    /** A walk that takes longer than this (in ticks) ends with a teleport. */
    static final int WALK_LIMIT = 600;
    /** Spoken lines carry this far. */
    static final double EARSHOT = 32.0D;

    public enum Voyage { DEPARTURE, ARRIVAL }

    private static Scene active;

    private Dispatcher() {
    }

    /** True while a dispatcher is on their way: Central's transfers wait for them. */
    public static boolean busy() {
        return active != null;
    }

    /**
     * Carries out {@code voyage} at the Central chest {@code chest}: {@code transfer} moves the letters and returns
     * what the dispatcher says (null: nothing moved, so nothing to say). Without a scene it runs now and the bell rings
     * at Central.
     */
    public static void voyage(Voyage voyage, Block chest, Supplier<String> transfer) {
        if (active == null && enabled() && (always() || audience(chest.getLocation())) && citizens()) {
            try {
                active = new Scene(voyage, chest, transfer);
                active.runTaskTimer(VA_postal.plugin, 1L, 5L);
                return;
            } catch (RuntimeException | LinkageError e) {
                active = null;
                Util.dinform("[Postal] Dispatcher unavailable (" + e.getMessage() + "); moving the mail directly.");
            }
        }
        if (transfer.get() != null) {
            bell(chest.getLocation().add(0.5D, 0.5D, 0.5D));
        }
    }

    /** Ends a scene now (shutdown): its transfer runs if it hadn't, and the NPC is destroyed. */
    public static void shutdown() {
        if (active != null) {
            active.finish(true);
        }
    }

    static boolean enabled() {
        return VA_postal.plugin.getConfig().getBoolean(GetConfig.path_format("network.dispatcher.enabled"), true);
    }

    /** {@code Network.Dispatcher.Always}: come even with nobody near Central to see it (for testing). */
    static boolean always() {
        return VA_postal.plugin.getConfig().getBoolean(GetConfig.path_format("network.dispatcher.always"), false);
    }

    static String config(String path, String fallback) {
        String v = VA_postal.plugin.getConfig().getString(GetConfig.path_format(path), fallback);
        return v == null ? fallback : v;
    }

    /** Fills a configured line: {@code %servers%} and {@code %count%}. */
    public static String line(Voyage voyage, String servers, int letters) {
        String template = config(voyage == Voyage.DEPARTURE ? "network.dispatcher.lines.departure" : "network.dispatcher.lines.arrival",
                voyage == Voyage.DEPARTURE ? "All aboard for %servers%! %count% for the voyage."
                        : "Mail from %servers%! %count% off the ship.");
        return template.replace("%servers%", servers).replace("%count%", letters + (letters == 1 ? " letter" : " letters"));
    }

    /** The departure/arrival bell ({@code Network.Sound}). */
    static void bell(Location at) {
        String sound = config("network.sound", "block.bell.use");
        if (!sound.isBlank() && at != null && at.getWorld() != null) {
            at.getWorld().playSound(at, sound.trim(), 4.0F, 1.0F);
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

    /** A mailbag: a bundle named for the job. */
    private static ItemStack mailbag() {
        ItemStack bag = new ItemStack(Material.BUNDLE);
        org.bukkit.inventory.meta.ItemMeta meta = bag.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Mailbag");
            bag.setItemMeta(meta);
        }
        return bag;
    }

    /** Walk in, open the chest, hand over (or take) the bag, walk off, vanish. Driven every 5 ticks. */
    private static final class Scene extends BukkitRunnable {
        private final Voyage voyage;
        private final Block chest;
        private final Location chest_center;
        private final Location start;
        private final Location stand;
        private final Supplier<String> transfer;
        private final NPC npc;
        private int phase;
        private int ticks;
        private boolean transferred;
        private boolean moved;
        private boolean done;

        Scene(Voyage voyage, Block chest, Supplier<String> transfer) {
            this.voyage = voyage;
            this.chest = chest;
            this.transfer = transfer;
            this.chest_center = chest.getLocation().add(0.5D, 0.5D, 0.5D);
            BlockFace facing = BlockFacing.facing(chest);
            Location in_front = facing == null ? null : Courier.stand_near(chest.getRelative(facing).getLocation(), 2);
            Location beside = in_front != null ? in_front : Courier.stand_near(chest.getLocation(), 2);
            if (beside == null) {
                throw new IllegalStateException("no room beside Central's chest");
            }
            int distance = Math.max(4, VA_postal.plugin.getConfig().getInt(GetConfig.path_format("network.dispatcher.distance"), 24));
            Location from = Courier.stand_near(chest.getLocation(), distance);
            this.stand = beside;
            this.start = from != null ? from : beside;
            this.npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER,
                    ChatColor.translateAlternateColorCodes('&', config("network.dispatcher.name", "&3Dispatcher")));
            npc.data().set(NPC.Metadata.SHOULD_SAVE, false);
            NpcLook.skin(npc, "dispatcher");
            npc.spawn(start);
            NpcLook.uniform(npc, "dispatcher", VA_postal.plugin.getConfig().getBoolean(GetConfig.path_format("network.dispatcher.uniform"), true));
            NpcLook.hold(npc, voyage == Voyage.ARRIVAL ? mailbag() : null);
            npc.getNavigator().getDefaultParameters().speedModifier(1.0F).range(Math.max(32.0F, distance * 2.0F));
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
                    case 0: // walking to Central's chest
                        if (near(stand) || ticks > WALK_LIMIT) {
                            if (!near(stand)) {
                                npc.teleport(stand, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                            }
                            npc.getNavigator().cancelNavigation();
                            npc.faceLocation(chest_center);
                            if (voyage == Voyage.ARRIVAL) {
                                bell(chest_center); // the mail has come in
                            }
                            lid(true);
                            phase = 1;
                            ticks = 0;
                        }
                        break;
                    case 1: // at the chest: the hand-over
                        npc.faceLocation(chest_center);
                        if (ticks >= 10) {
                            if (npc.getEntity() instanceof LivingEntity) {
                                ((LivingEntity) npc.getEntity()).swingMainHand();
                            }
                            String said = run_transfer();
                            NpcLook.hold(npc, voyage == Voyage.DEPARTURE && moved ? mailbag() : null);
                            if (said != null) {
                                say(said);
                            }
                            phase = 2;
                            ticks = 0;
                        }
                        break;
                    case 2: // close up, turn and walk off
                        if (ticks >= 15) {
                            lid(false);
                            npc.getNavigator().setTarget(start);
                            phase = 3;
                            ticks = 0;
                        }
                        break;
                    default: // gone, once back where they came from (or after a while)
                        if (near(start) || ticks > WALK_LIMIT) {
                            if (voyage == Voyage.DEPARTURE && moved) {
                                bell(npc.getEntity().getLocation()); // the ship sails
                            }
                            finish(false);
                        }
                }
            } catch (RuntimeException e) {
                finish(true);
            }
        }

        private boolean near(Location at) {
            return npc.getEntity().getLocation().distanceSquared(at) < 2.5D;
        }

        private String run_transfer() {
            if (transferred) {
                return null;
            }
            transferred = true;
            String said = transfer.get();
            moved = said != null;
            return said;
        }

        private void lid(boolean open) {
            try {
                if (chest.getState() instanceof Lidded lidded) {
                    if (open) {
                        lidded.open();
                    } else {
                        lidded.close();
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        private void say(String line) {
            Util.cinform("[Postal] " + ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',
                    config("network.dispatcher.name", "&3Dispatcher"))) + " at Central: " + line);
            String message = ChatColor.translateAlternateColorCodes('&', "&7<" + config("network.dispatcher.name", "&3Dispatcher")
                    + "&7> &f" + line);
            for (Player p : chest.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(chest_center) <= EARSHOT * EARSHOT) {
                    p.sendMessage(message);
                }
            }
        }

        void finish(boolean abrupt) {
            if (done) {
                return;
            }
            done = true;
            if (!transferred) {
                // Whatever happened to the dispatcher, the mail moves.
                if (run_transfer() != null) {
                    bell(chest_center);
                }
            }
            lid(false);
            try {
                if (npc.isSpawned() && !abrupt) {
                    Location at = npc.getEntity().getLocation().add(0.0D, 1.0D, 0.0D);
                    at.getWorld().spawnParticle(Particle.CLOUD, at, 20, 0.3D, 0.6D, 0.3D, 0.02D);
                }
                npc.destroy();
            } catch (RuntimeException ignored) {
            }
            active = null;
            try {
                cancel();
            } catch (IllegalStateException ignored) {
            }
        }
    }
}
