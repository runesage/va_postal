package com.vodhanel.minecraft.va_postal.navigation.survey;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Address;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.C_Route;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Move;
import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Point;
import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Result;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Surveys an address's route (docs/design/routes.md, option A): from its post office to the address, over the
 * blocks as they are now, saved as an ordinary waypoint route. The chunks are loaded (never generated) and
 * snapshotted on the main thread; the search runs off it.
 */
public final class RouteSurvey {
    /** A survey may cover at most this many chunks (a box about 600 blocks across). */
    static final int MAX_CHUNKS = 1500;

    private static final Deque<Runnable> queue = new ArrayDeque<>();
    private static boolean running;

    private RouteSurvey() {
    }

    /** The outcome reported to whoever asked: a line of text, and whether a route was saved. */
    public record Outcome(String office, String address, boolean saved, String message) {
    }

    /** Surveys one address's route, after any surveys already queued; {@code done} runs on the main thread. */
    public static void survey(String office, String address, Consumer<Outcome> done) {
        synchronized (queue) {
            queue.add(() -> run(office, address, outcome -> {
                done.accept(outcome);
                next();
            }));
            if (!running) {
                running = true;
                Bukkit.getScheduler().runTask(VA_postal.plugin, RouteSurvey::next);
            }
        }
    }

    private static void next() {
        Runnable r;
        synchronized (queue) {
            r = queue.poll();
            if (r == null) {
                running = false;
                return;
            }
        }
        try {
            r.run();
        } catch (RuntimeException e) {
            Util.cinform("[Postal] Route survey failed: " + e);
            next();
        }
    }

    /** The addresses of a post office, as named in the config. */
    public static List<String> addresses(String office) {
        ConfigurationSection s = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("address." + office));
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    /** True if a postman is on this address's route right now (its route can't change under him). */
    static boolean in_use(String office, String address) {
        if (VA_postal.wtr_goal_active == null) {
            return false;
        }
        for (int i = 0; i < VA_postal.wtr_goal_active.length; i++) {
            if (VA_postal.wtr_goal_active[i] && office.equalsIgnoreCase(VA_postal.wtr_poffice[i])
                    && address.equalsIgnoreCase(VA_postal.wtr_address[i])) {
                return true;
            }
        }
        return false;
    }

    private static void run(String office, String address, Consumer<Outcome> done) {
        SurveyGrid.init();
        Location from = Util.str2location(C_Postoffice.get_local_po_location_by_name(office));
        Location to = Util.str2location(C_Address.get_address_location(office, address));
        if (from == null || to == null) {
            done.accept(new Outcome(office, address, false, from == null ? "the post office has no location" : "the address has no location"));
            return;
        }
        if (!from.getWorld().equals(to.getWorld())) {
            done.accept(new Outcome(office, address, false, "the address is in another world from its post office"));
            return;
        }
        if (in_use(office, address)) {
            done.accept(new Outcome(office, address, false, "a postman is on this route right now; try again when he's back"));
            return;
        }
        World world = from.getWorld();
        int fx = from.getBlockX(), fy = from.getBlockY(), fz = from.getBlockZ();
        int tx = to.getBlockX(), ty = to.getBlockY(), tz = to.getBlockZ();
        int pad = Surveyor.MARGIN + 3;
        int cx1 = (Math.min(fx, tx) - pad) >> 4, cx2 = (Math.max(fx, tx) + pad) >> 4;
        int cz1 = (Math.min(fz, tz) - pad) >> 4, cz2 = (Math.max(fz, tz) + pad) >> 4;
        if ((long) (cx2 - cx1 + 1) * (cz2 - cz1 + 1) > MAX_CHUNKS) {
            done.accept(new Outcome(office, address, false, "the address is too far from its post office to survey"));
            return;
        }
        List<CompletableFuture<Chunk>> loading = new ArrayList<>();
        for (int cx = cx1; cx <= cx2; cx++) {
            for (int cz = cz1; cz <= cz2; cz++) {
                if (world.isChunkGenerated(cx, cz)) {
                    loading.add(world.getChunkAtAsync(cx, cz, false));
                }
            }
        }
        CompletableFuture.allOf(loading.toArray(new CompletableFuture[0])).whenComplete((v, err) ->
                Bukkit.getScheduler().runTask(VA_postal.plugin, () -> {
                    Map<Long, ChunkSnapshot> snaps = new HashMap<>();
                    for (CompletableFuture<Chunk> f : loading) {
                        Chunk c = f.getNow(null);
                        if (c != null) {
                            snaps.put(SurveyGrid.chunk_key(c.getX(), c.getZ()), c.getChunkSnapshot(false, false, false));
                        }
                    }
                    SurveyGrid grid = new SurveyGrid(snaps, world.getMinHeight(), world.getMaxHeight());
                    Bukkit.getScheduler().runTaskAsynchronously(VA_postal.plugin, () -> {
                        long t0 = System.nanoTime();
                        Result r = new Surveyor(grid).survey(fx, fy, fz, tx, ty, tz);
                        long ms = (System.nanoTime() - t0) / 1_000_000L;
                        Bukkit.getScheduler().runTask(VA_postal.plugin, () -> done.accept(save(world, office, address, r, ms)));
                    });
                }));
    }

    private static Outcome save(World world, String office, String address, Result r, long ms) {
        if (!r.ok()) {
            return new Outcome(office, address, false, "no route found: " + r.failure());
        }
        if (in_use(office, address)) {
            return new Outcome(office, address, false, "a postman set off on this route during the survey; try again when he's back");
        }
        C_Route.delete_route(office, address);
        List<Point> w = r.waypoints();
        for (int i = 0; i < w.size(); i++) {
            Point p = w.get(i);
            C_Route.set_route_waypoint_ns(office, address, i, Util.location2str(new Location(world, p.x(), p.y(), p.z())));
        }
        C_Route.save_config();
        List<String> notes = new ArrayList<>();
        if (r.path().stream().anyMatch(p -> p.move() == Move.DOOR)) {
            notes.add("doors");
        }
        if (r.path().stream().anyMatch(p -> p.move() == Move.LADDER)) {
            notes.add("a ladder");
        }
        return new Outcome(office, address, true, w.size() + " waypoints over " + (r.path().size() - 1) + " blocks"
                + (notes.isEmpty() ? "" : ", through " + String.join(" and ", notes)) + " (" + r.expanded()
                + " positions searched, " + ms + " ms)");
    }

    // ---- /postal survey ------------------------------------------------------------------------

    /**
     * {@code /postal survey <office> [address | missing | all]}: surveys one address's route, the addresses that
     * have no route ({@code missing}, the default), or every address of the office ({@code all}, replacing
     * hand-made routes).
     */
    public static void command(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "&7Usage: /postal survey <post office> [address | missing | all]");
            return;
        }
        String office = C_Postoffice.town_complete(args[1]);
        if ("null".equals(office)) {
            send(sender, "&7Unknown post office. See /tlist.");
            return;
        }
        String which = args.length > 2 ? args[2] : "missing";
        List<String> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(which) || "missing".equalsIgnoreCase(which)) {
            for (String a : addresses(office)) {
                if ("all".equalsIgnoreCase(which) || !C_Route.is_route_defined(office, a)) {
                    targets.add(a);
                }
            }
        } else {
            String address = C_Address.addresses_complete(office, which);
            if ("null".equals(address)) {
                send(sender, "&7Unknown address in " + office + ". See /alist " + office + ".");
                return;
            }
            targets.add(address);
        }
        if (targets.isEmpty()) {
            send(sender, "&7Every address in " + office + " already has a route. Use &f/postal survey " + office
                    + " all&7 to survey them again.");
            return;
        }
        send(sender, "&6[Postal] Surveying " + targets.size() + " route" + (targets.size() == 1 ? "" : "s") + " in " + office + "...");
        for (String a : targets) {
            survey(office, a, o -> report(sender, o));
        }
    }

    /** Tells {@code sender} (and the console) how a survey went. */
    public static void report(CommandSender sender, Outcome o) {
        String line = (o.saved() ? "&a" : "&c") + "Route " + o.office() + ", " + o.address() + ": &7" + o.message();
        send(sender, line);
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            Util.cinform("[Postal] Route survey " + o.office() + ", " + o.address() + ": " + o.message());
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
