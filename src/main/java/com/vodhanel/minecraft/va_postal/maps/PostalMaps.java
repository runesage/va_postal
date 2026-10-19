package com.vodhanel.minecraft.va_postal.maps;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.C_Route;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.economy.EconomyState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Postal maps (docs/design/central-office.md §6): ordinary map items Postal draws itself. The network map shows
 * Central, every office in its world and Central's routes out to them; an office map shows one office, its routes
 * and its addresses. Bought at the counters (Central's for the network, an office's for its own), given by admins,
 * or hung on the notice board. Each map's id is recorded ({@code maps.<id>} in config), so its renderer is put back
 * when the map is loaded again after a restart.
 */
public final class PostalMaps implements Listener {
    /** The office a network map shows: none. */
    static final String NETWORK = "network";
    /** How long a drawing lasts before it's redrawn with the network as it is then. */
    private static final long REDRAW_MS = 5L * 60L * 1000L;

    private static final Color PARCHMENT = new Color(222, 206, 160);
    private static final Color EDGE = new Color(150, 120, 80);
    private static final Color ROUTE = new Color(120, 85, 50);
    private static final Color WALK = new Color(60, 110, 60);
    private static final Color OFFICE = new Color(150, 30, 30);
    private static final Color CENTRAL = new Color(30, 50, 140);

    // ---- Registry ---------------------------------------------------------------------------

    private static String path(int id) {
        return GetConfig.path_format("maps.m" + id);
    }

    /** What map {@code id} shows: {@link #NETWORK}, an office's name, or null if it isn't a Postal map. */
    static String shows(int id) {
        return VA_postal.plugin.getConfig().getString(path(id));
    }

    private static void record(int id, String what) {
        VA_postal.plugin.getConfig().set(path(id), what);
        VA_postal.plugin.saveConfig();
    }

    /** Puts Postal's renderer on {@code view} (in place of the vanilla one), if it's a Postal map. */
    static boolean attach(MapView view) {
        String what = view == null ? null : shows(view.getId());
        if (what == null) {
            return false;
        }
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(r);
        }
        view.addRenderer(new Renderer(what));
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        return true;
    }

    @EventHandler
    public void on_map_init(MapInitializeEvent event) {
        attach(event.getMap());
    }

    /** Puts the renderers back on every recorded map (at start-up). Returns how many. */
    public static int attach_all() {
        ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("maps"));
        int n = 0;
        if (cs == null) {
            return 0;
        }
        for (String key : cs.getKeys(false)) {
            try {
                MapView view = Bukkit.getMap(Integer.parseInt(key.substring(1)));
                if (view != null && attach(view)) {
                    n++;
                }
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                // not one of ours
            }
        }
        return n;
    }

    // ---- Items ------------------------------------------------------------------------------

    /** A new map of the network ({@code office} null) or of {@code office}; null if there's nothing to draw. */
    public static ItemStack create(String office) {
        Location at = Util.str2location(office == null ? C_Postoffice.get_central_po_location()
                : C_Postoffice.get_local_po_location_by_name(office));
        if (at == null || at.getWorld() == null) {
            return null;
        }
        MapView view = Bukkit.createMap(at.getWorld());
        record(view.getId(), office == null ? NETWORK : office.toLowerCase(Locale.ROOT).trim());
        attach(view);
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.displayName(plain(office == null ? "&6Postal network map" : "&6Map of " + DeedDocument.display(office)));
        meta.lore(List.of(plain(office == null ? "&7Central and its post offices" : "&7The post office, its routes and addresses")));
        item.setItemMeta(meta);
        return item;
    }

    private static net.kyori.adventure.text.Component plain(String legacy) {
        return Text.component(Text.color(legacy)).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    /** What a map costs at a counter ({@code Settings.Maps.Price}, default 10); free without an economy. */
    public static double price() {
        if (!VA_postal.economy_configured) {
            return 0.0D;
        }
        return Math.max(0.0D, GetConfig.double_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.maps.price"), 10.0D));
    }

    /** "$10.00", or "free". */
    public static String price_text() {
        double p = price();
        return p <= 0.0D ? "free" : P_Economy.ef(p);
    }

    /** {@code player} buys a map at a counter; the price goes to Central. */
    public static void buy(Player player, String office) {
        double p = price();
        if (p > 0.0D && !P_Economy.does_player_have_amount(player, p)) {
            Util.pinform(player, "&7A map costs " + price_text() + ".");
            return;
        }
        ItemStack map = create(office);
        if (map == null) {
            Util.pinform(player, "&7There's nothing to put on that map yet.");
            return;
        }
        if (p > 0.0D) {
            if (!P_Economy.withdraw_from_player(player, p)) {
                Util.pinform(player, "&7A map costs " + price_text() + ".");
                return;
            }
            P_Economy.deposit_to_central(p);
            EconomyState.record(EconomyState.Flow.MAP_SALE, p);
        }
        give(player, map);
        Util.pinform(player, "&6Here is your " + (office == null ? "map of the network" : "map of " + DeedDocument.display(office)) + ".");
    }

    private static void give(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    // ---- /postal map ------------------------------------------------------------------------

    /** {@code /postal map network|<office> [player] | frame [x y z [world]] | list} (admins). */
    public static void command(CommandSender sender, String[] args) {
        if (sender instanceof Player && !sender.hasPermission("postal.admin")) {
            send(sender, "&7Required permission not present.");
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "list" -> {
                ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("maps"));
                List<String> keys = cs == null ? List.of() : new ArrayList<>(cs.getKeys(false));
                send(sender, "&6[Postal] Postal maps: " + keys.size());
                for (String k : keys) {
                    String what = cs.getString(k);
                    send(sender, "&7map " + k.substring(1) + ": &f" + (NETWORK.equals(what) ? "the network" : DeedDocument.display(what)));
                }
            }
            case "frame" -> frame(sender, args);
            case "" -> send(sender, "&7/postal map network|<office> [player] | frame [x y z [world]] | list");
            default -> {
                String office = null;
                if (!NETWORK.equals(sub)) {
                    office = C_Postoffice.town_complete(args[1]);
                    if ("null".equals(office)) {
                        send(sender, "&7No post office matches '" + args[1] + "'. See /tlist.");
                        return;
                    }
                }
                Player to = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
                if (to == null) {
                    send(sender, "&7Give it to whom? /postal map " + sub + " <player>");
                    return;
                }
                ItemStack map = create(office);
                if (map == null) {
                    send(sender, "&7There's nothing to put on that map yet.");
                    return;
                }
                give(to, map);
                send(sender, "&6Gave " + to.getName() + " a " + (office == null ? "network map" : "map of " + DeedDocument.display(office)) + ".");
            }
        }
    }

    /** Hangs a network map, fixed, in the item frame looked at (or at the coordinates): the notice board's map. */
    private static void frame(CommandSender sender, String[] args) {
        ItemFrame frame = null;
        if (args.length >= 5) {
            try {
                World w = args.length > 5 ? Bukkit.getWorld(args[5]) : sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                Location at = new Location(w, Integer.parseInt(args[2]) + 0.5D, Integer.parseInt(args[3]) + 0.5D, Integer.parseInt(args[4]) + 0.5D);
                for (Entity e : w.getNearbyEntities(at, 0.8D, 0.8D, 0.8D)) {
                    if (e instanceof ItemFrame f) {
                        frame = f;
                        break;
                    }
                }
            } catch (NumberFormatException | NullPointerException e) {
                frame = null;
            }
        } else if (sender instanceof Player p && p.getTargetEntity(6) instanceof ItemFrame f) {
            frame = f;
        }
        if (frame == null) {
            send(sender, "&7Look at an item frame, or give its coordinates.");
            return;
        }
        ItemStack map = create(null);
        if (map == null) {
            send(sender, "&7Central isn't set up (/setcentral).");
            return;
        }
        frame.setItem(map, false);
        frame.setFixed(true);
        Location l = frame.getLocation();
        send(sender, "&6The network map hangs at " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + ".");
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Text.color(message));
    }

    // ---- Drawing ----------------------------------------------------------------------------

    /** Draws a network or office map, and redraws it every few minutes so it keeps up with the network. */
    static final class Renderer extends MapRenderer {
        private final String what;
        private long drawn_at;

        Renderer(String what) {
            super(false);
            this.what = what;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            long now = System.currentTimeMillis();
            if (drawn_at != 0L && now - drawn_at < REDRAW_MS) {
                return;
            }
            drawn_at = now;
            for (int x = 0; x < MapGeometry.SIZE; x++) {
                for (int y = 0; y < MapGeometry.SIZE; y++) {
                    boolean edge = x == 0 || y == 0 || x == MapGeometry.SIZE - 1 || y == MapGeometry.SIZE - 1;
                    canvas.setPixelColor(x, y, edge ? EDGE : PARCHMENT);
                }
            }
            MapCursorCollection cursors = new MapCursorCollection();
            if (NETWORK.equals(what)) {
                network(canvas, cursors);
            } else {
                office(canvas, cursors, what);
            }
            canvas.setCursors(cursors);
        }

        private static void network(MapCanvas canvas, MapCursorCollection cursors) {
            Location central = Util.str2location(C_Postoffice.get_central_po_location());
            if (central == null) {
                return;
            }
            List<String> names = new ArrayList<>();
            List<Location> offices = new ArrayList<>();
            ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("postoffice.local"));
            if (cs != null) {
                for (String key : cs.getKeys(false)) {
                    Location l = Util.str2location(C_Postoffice.get_local_po_location_by_name(key));
                    if (l != null && l.getWorld() == central.getWorld()) {
                        names.add(key);
                        offices.add(l);
                    }
                }
            }
            List<double[]> points = new ArrayList<>();
            points.add(new double[]{central.getX(), central.getZ()});
            for (Location l : offices) {
                points.add(new double[]{l.getX(), l.getZ()});
            }
            MapGeometry.Projection p = MapGeometry.fit(points, 14);
            int[] c = p.pixel(central.getX(), central.getZ());
            for (Location l : offices) {
                int i = 0;
                for (int[] px : MapGeometry.line(c, p.pixel(l.getX(), l.getZ()))) {
                    if (i++ % 3 != 2) { // dashed: Central's postman's runs
                        canvas.setPixelColor(px[0], px[1], ROUTE);
                    }
                }
            }
            for (int i = 0; i < offices.size(); i++) {
                Location l = offices.get(i);
                dot(canvas, p.pixel(l.getX(), l.getZ()), 1, OFFICE);
                cursor(cursors, p.cursor(l.getX(), l.getZ()), MapCursor.Type.BANNER_RED, DeedDocument.display(names.get(i)));
            }
            dot(canvas, c, 2, CENTRAL);
            cursor(cursors, p.cursor(central.getX(), central.getZ()), MapCursor.Type.BANNER_BLUE, "Central");
            canvas.drawText(3, 3, MinecraftFont.Font, "Postal network");
        }

        private static void office(MapCanvas canvas, MapCursorCollection cursors, String office) {
            Location po = Util.str2location(C_Postoffice.get_local_po_location_by_name(office));
            if (po == null) {
                return;
            }
            String[] addresses = C_Arrays.addresses_list(office);
            List<List<Location>> routes = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<double[]> points = new ArrayList<>();
            points.add(new double[]{po.getX(), po.getZ()});
            if (addresses != null) {
                for (String a : addresses) {
                    List<Location> route = new ArrayList<>();
                    for (Location l : C_Route.get_waypoint_locations(office, a.trim())) {
                        if (l != null && l.getWorld() == po.getWorld()) {
                            route.add(l);
                            points.add(new double[]{l.getX(), l.getZ()});
                        }
                    }
                    routes.add(route);
                    names.add(a.trim());
                }
            }
            MapGeometry.Projection p = MapGeometry.fit(points, 12);
            for (List<Location> route : routes) {
                int[] from = p.pixel(po.getX(), po.getZ());
                for (Location l : route) {
                    int[] to = p.pixel(l.getX(), l.getZ());
                    for (int[] px : MapGeometry.line(from, to)) {
                        canvas.setPixelColor(px[0], px[1], WALK);
                    }
                    from = to;
                }
            }
            for (int i = 0; i < routes.size(); i++) {
                List<Location> route = routes.get(i);
                if (route.isEmpty()) {
                    continue;
                }
                Location end = route.get(route.size() - 1);
                dot(canvas, p.pixel(end.getX(), end.getZ()), 1, ROUTE);
                cursor(cursors, p.cursor(end.getX(), end.getZ()), MapCursor.Type.BANNER_WHITE, DeedDocument.display(names.get(i)));
            }
            dot(canvas, p.pixel(po.getX(), po.getZ()), 2, OFFICE);
            cursor(cursors, p.cursor(po.getX(), po.getZ()), MapCursor.Type.BANNER_RED, DeedDocument.display(office) + " Post Office");
            canvas.drawText(3, 3, MinecraftFont.Font, DeedDocument.display(office));
        }

        private static void dot(MapCanvas canvas, int[] at, int r, Color color) {
            for (int x = at[0] - r; x <= at[0] + r; x++) {
                for (int y = at[1] - r; y <= at[1] + r; y++) {
                    if (x >= 0 && y >= 0 && x < MapGeometry.SIZE && y < MapGeometry.SIZE) {
                        canvas.setPixelColor(x, y, color);
                    }
                }
            }
        }

        private static void cursor(MapCursorCollection cursors, byte[] at, MapCursor.Type type, String caption) {
            cursors.addCursor(new MapCursor(at[0], at[1], (byte) 8, type, true, caption));
        }
    }
}
