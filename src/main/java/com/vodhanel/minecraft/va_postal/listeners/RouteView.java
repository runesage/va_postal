package com.vodhanel.minecraft.va_postal.listeners;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Particles;
import com.vodhanel.minecraft.va_postal.config.C_Route;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A temporary /showroute highlight: waypoint blocks shown as the highlight block and particle lines
 * between them, for one player (or, from the console, everyone online), hidden again after a while.
 * <p>
 * Each view has its own tasks. v4 drove /showroute through the route editor's single highlighter and
 * marker list, so a second /showroute leaked the first one's tasks, the delayed hide cancelled whatever
 * highlighter was current (a route editor session started in the meantime included), and the console
 * version showed nothing because it had no player to send the blocks to.
 */
public final class RouteView {
    /** How long a /showroute highlight stays up (v4: 1000 ticks). */
    static final long SHOW_TICKS = 1000L;
    private static final String CONSOLE = "\0console";
    private static final Map<String, RouteView> views = new HashMap<>();

    private final Player viewer; // null: everyone online
    private final List<Location> waypoints;
    private final BlockData highlight;
    private int particle_task = -1;
    private int block_task = -1;
    private int hide_task = -1;

    private RouteView(Player viewer, List<Location> waypoints, BlockData highlight) {
        this.viewer = viewer;
        this.waypoints = waypoints;
        this.highlight = highlight;
    }

    /** Shows a route to {@code viewer} (null: everyone online), replacing that viewer's previous view. */
    public static synchronized void show(Player viewer, String stown, String saddress) {
        hide(viewer);
        List<Location> waypoints = new ArrayList<>();
        for (Location l : C_Route.get_waypoint_locations(stown, saddress)) {
            if (l != null && l.getWorld() != null) {
                waypoints.add(l);
            }
        }
        RouteView view = new RouteView(viewer, waypoints, GetConfig.wpnt_hilite_id());
        views.put(key(viewer), view);
        view.particle_task = Bukkit.getScheduler().scheduleSyncRepeatingTask(VA_postal.plugin, view::draw_lines, 0L, 10L);
        view.block_task = Bukkit.getScheduler().scheduleSyncRepeatingTask(VA_postal.plugin, view::draw_blocks, 0L, 20L);
        view.hide_task = Bukkit.getScheduler().scheduleSyncDelayedTask(VA_postal.plugin, () -> {
            synchronized (RouteView.class) {
                // Only end this view: the viewer may have started a newer one since.
                if (views.get(key(viewer)) == view) {
                    views.remove(key(viewer));
                    view.stop();
                }
            }
        }, SHOW_TICKS);
    }

    /** Hides {@code viewer}'s view (null: the console's), if any. */
    public static synchronized void hide(Player viewer) {
        RouteView view = views.remove(key(viewer));
        if (view != null) {
            view.stop();
        }
    }

    /** Hides every view, e.g. on plugin disable. */
    public static synchronized void hide_all() {
        for (RouteView view : views.values()) {
            view.stop();
        }
        views.clear();
    }

    private static String key(Player viewer) {
        return viewer == null ? CONSOLE : viewer.getUniqueId().toString();
    }

    private Collection<? extends Player> audience() {
        if (viewer == null) {
            return Bukkit.getOnlinePlayers();
        }
        return viewer.isOnline() ? Collections.singletonList(viewer) : Collections.emptyList();
    }

    private void draw_blocks() {
        for (Player p : audience()) {
            for (Location l : waypoints) {
                if (Objects.equals(l.getWorld(), p.getWorld())) {
                    p.sendBlockChange(l, highlight);
                }
            }
        }
    }

    private void draw_lines() {
        for (Player p : audience()) {
            Location previous = null;
            for (Location l : waypoints) {
                if (previous != null && Objects.equals(l.getWorld(), p.getWorld())) {
                    Particles.displayLine(previous.clone().add(0.5, 1.5, 0.5), l.clone().add(0.5, 1.5, 0.5), p, RouteEditor.getConfiguredPE());
                }
                previous = l;
            }
        }
    }

    private void stop() {
        Bukkit.getScheduler().cancelTask(particle_task);
        Bukkit.getScheduler().cancelTask(block_task);
        Bukkit.getScheduler().cancelTask(hide_task);
        for (Player p : audience()) {
            for (Location l : waypoints) {
                if (Objects.equals(l.getWorld(), p.getWorld())) {
                    p.sendBlockChange(l, l.getBlock().getBlockData());
                }
            }
        }
    }
}
