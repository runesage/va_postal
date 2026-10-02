package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.config.C_Address;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.C_Route;
import java.util.Collection;
import org.bukkit.Location;
import org.dynmap.markers.Marker;

public class P_Dynmap {
    VA_postal plugin;
    private static int red = 16711680;
    private static int green = 65280;
    private static int blue = 255;
    private static int white = 16777215;
    private static int black = 0;
    private static int grey = 12105912;
    private static int yellow = 16776960;
    private static int orange = 16750848;
    private static int cyan = 65535;
    private static int brown = 10053120;
    private static int magenta = 12074424;
    private static int pink = 16735487;
    private static int postal_blue = 5079807;
    private static boolean dynmap_markerset_created = false;
    public static Collection<String> disabled_worlds = null;
    private static boolean creating_labels = false;
    public static int dyn_po_cnt = 0;
    public static int dyn_addr_cnt = 0;
    public static int dyn_postmen_cnt = 0;
    public static boolean deleting_routes = false;
    private static boolean running_delayed_nulls = false;

    public P_Dynmap(VA_postal instance) {
        this.plugin = instance;
    }

    public static void dynmap_stop() {
        if (VA_postal.dynmap_configured) {
            if (VA_postal.dynmap_active) {
                VA_postal.dynmap_active = false;
                if (VA_postal.dynmap.isEnabled()) {
                    delete_postmen();
                    delete_routes();
                    long delay = VA_Dispatcher.restart_cool / 2L;
                    delayed_nulls(delay);
                    if (VA_postal.markerset != null) {
                        VA_postal.markerset.deleteMarkerSet();
                        VA_postal.markerset = null;
                        dynmap_markerset_created = false;
                    }
                }
            }
        }
    }

    public static void dynmap_start() {
        if (VA_postal.dynmap_configured) {
            if (VA_postal.dynmap.isEnabled()) {
                VA_postal.dynmap_active = false;

                try {
                    delete_static_labels();
                    if (!dynmap_markerset_created) {
                        VA_postal.markerapi = VA_postal.apiDynmap.getMarkerAPI();
                        if (VA_postal.markerapi == null) {
                            Util.cinform("Cannot find dynmap marker api!");
                            return;
                        }

                        VA_postal.markerset = VA_postal.markerapi.getMarkerSet("postal.markerset");
                        if (VA_postal.markerset == null) {
                            VA_postal.markerset = VA_postal.markerapi.createMarkerSet("postal.markerset", "Postal", null, false);
                        } else {
                            VA_postal.markerset.setMarkerSetLabel("Postal");
                        }

                        if (VA_postal.markerset == null) {
                            Util.cinform("Cannot create dynmap marker set!");
                            return;
                        }

                        int minzoom = 0;
                        if (minzoom > 0) {
                            VA_postal.markerset.setMinZoom(minzoom);
                        }

                        VA_postal.markerset.setLayerPriority(1);
                        VA_postal.markerset.setHideByDefault(false);
                        VA_postal.markerset.setLabelShow(true);
                        dynmap_markerset_created = true;
                    }

                    create_static_labels();
                } catch (Exception e) {
                    return;
                }

                VA_postal.dynmap_active = true;
            }
        }
    }

    private static boolean is_world_enabled(String sworld) {
        return true;
    }

    public static void delete_postman(String stown) {
        int index = -1;

        for (int i = 0; i < dyn_postmen_cnt; i++) {
            if (VA_postal.dyn_postman_po[i] != null && VA_postal.dyn_postman_po[i].equalsIgnoreCase(stown)) {
                index = i;
                break;
            }
        }

        if (index != -1) {
            if (VA_postal.dyn_postman[index] != null) {
                VA_postal.dyn_postman[index].setLineStyle(1, 0.1, black);
                VA_postal.dyn_postman[index].setFillStyle(0.1, black);
                VA_postal.dyn_postman[index].setRadius(1.0, 1.0);
                if (VA_postal.dyn_postman_po[index] != null) {
                    VA_postal.dyn_postman_po[index] = null;
                }
            }
        }
    }

    public static void create_po_label(String stown) {
        int index = -1;

        for (int i = 0; i < VA_postal.dyn_po_str.length; i++) {
            if (VA_postal.dyn_po_str[i] == null) {
                index = i;
                break;
            }
        }

        if (index != -1) {
            String sowner = "server";
            if (C_Owner.is_local_po_owner_defined(stown)) {
                sowner = C_Owner.get_owner_local_po(stown);
            }

            String[] addr_list = C_Arrays.addresses_list(stown);
            int addr_cnt = 0;
            if (addr_list != null) {
                addr_cnt = addr_list.length;
            }

            addr_list = null;
            Location location = Util.str2location(C_Postoffice.get_local_po_location_by_name(stown));
            String sid = null;
            String saddr_cnt = Util.int2str(index);
            sid = "po_" + saddr_cnt;
            String sworld = location.getWorld().getName();
            double X = location.getX();
            double Y = location.getY();
            double Z = location.getZ();
            if (VA_postal.dyn_po_mrkr[index] != null) {
                VA_postal.dyn_po_mrkr[index].deleteMarker();
                VA_postal.dyn_po_mrkr[index] = null;
            }

            String msg;
            if (VA_postal.towny_configured && VA_postal.towny_opt_in && P_Towny.is_towny_by_db(stown)) {
                String townt_t = P_Towny.get_town_name_by_db(stown);
                String blocks = Util.int2str(P_Towny.get_town_blocks_by_db(stown));
                msg = "Post office in "
                    + df(townt_t)
                    + ", named "
                    + df(stown)
                    + ", owned by "
                    + df(sowner)
                    + ", services "
                    + saddr_cnt
                    + " addresses."
                    + "  This town contains "
                    + blocks
                    + " town blocks.";
            } else {
                msg = "Post office for " + df(stown) + ", owned by " + df(sowner) + ", services " + saddr_cnt + " addresses.";
            }

            VA_postal.dyn_po_mrkr[index] = VA_postal.markerset.createMarker(sid, df(stown), sworld, X, Y, Z, VA_postal.dyn_postoffice_ico, false);
            VA_postal.dyn_po_mrkr[index].setDescription(msg);
            VA_postal.dyn_po_str[index] = stown;
            dyn_po_cnt++;
        }
    }

    public static void delete_po_label(String stown) {
        int index = -1;

        for (int i = 0; i < dyn_po_cnt; i++) {
            if (VA_postal.dyn_po_str[i] != null && VA_postal.dyn_po_str[i].equalsIgnoreCase(stown)) {
                index = i;
                break;
            }
        }

        if (index != -1) {
            VA_postal.dyn_po_mrkr[index].deleteMarker();
            VA_postal.dyn_po_mrkr[index] = null;
            VA_postal.dyn_po_str[index] = null;
        }
    }

    public static void create_addr_label(String stown, String saddress) {
        int index = -1;

        for (int i = 0; i < VA_postal.dyn_addr_str_po.length; i++) {
            if (VA_postal.dyn_addr_str_po[i] == null && VA_postal.dyn_addr_str_addr[i] == null) {
                index = i;
                break;
            }
        }

        if (index != -1) {
            String sowner = "server";
            if (C_Owner.is_address_owner_defined(stown, saddress)) {
                sowner = C_Owner.get_owner_address(stown, saddress);
            }

            Location location = Util.str2location(C_Address.get_address_location(stown, saddress));
            String sid = "addr_" + Util.int2str(index);
            String sworld = location.getWorld().getName();
            double X = location.getX();
            double Y = location.getY();
            double Z = location.getZ();
            if (VA_postal.dyn_addr_mrkr[index] != null) {
                VA_postal.dyn_addr_mrkr[index].deleteMarker();
                VA_postal.dyn_addr_mrkr[index] = null;
            }

            String msg;
            if (VA_postal.towny_configured && VA_postal.towny_opt_in && P_Towny.is_towny_by_db(stown)) {
                String townt_t = P_Towny.get_town_name_by_db(stown);
                msg = "Address: " + df(saddress) + ", post office name: " + df(stown) + ", owned by: " + df(sowner) + ", in town: " + df(townt_t);
            } else {
                msg = "Address: " + df(saddress) + ", in town: " + df(stown) + ", owned by: " + df(sowner);
            }

            VA_postal.dyn_addr_mrkr[index] = VA_postal.markerset.createMarker(sid, df(saddress), sworld, X, Y, Z, VA_postal.dyn_address_ico, false);
            VA_postal.dyn_addr_mrkr[index].setDescription(msg);
            VA_postal.dyn_addr_str_po[index] = stown;
            VA_postal.dyn_addr_str_addr[index] = saddress;
            dyn_addr_cnt++;
        }
    }

    public static void delete_addr_label(String stown, String saddress) {
        int index = -1;

        for (int i = 0; i < dyn_addr_cnt; i++) {
            if (VA_postal.dyn_addr_str_po[i] != null
                && VA_postal.dyn_addr_str_addr[i] != null
                && VA_postal.dyn_addr_str_po[i].equalsIgnoreCase(stown)
                && VA_postal.dyn_addr_str_addr[i].equalsIgnoreCase(saddress)) {
                index = i;
                break;
            }
        }

        if (index != -1) {
            VA_postal.dyn_addr_mrkr[index].deleteMarker();
            VA_postal.dyn_addr_mrkr[index] = null;
            VA_postal.dyn_addr_str_po[index] = null;
            VA_postal.dyn_addr_str_addr[index] = null;
        }
    }

    public static void create_static_labels() {
        if (VA_postal.dynmap_configured) {
            if (!creating_labels) {
                creating_labels = true;
                VA_postal.plugin
                    .getServer()
                    .getScheduler()
                    .runTaskLater(
                        VA_postal.plugin,
                        new Runnable() {
                            @Override
                            public void run() {
                                VA_postal.dyn_postoffice_ico = VA_postal.markerapi.getMarkerIcon("world");
                                VA_postal.dyn_address_ico = VA_postal.markerapi.getMarkerIcon("default");
                                String[] town_list = C_Arrays.town_list();
                                int address_count = 0;
                                if (town_list == null) {
                                    Util.cinform("Dynmap - Problem getting town array.");
                                    P_Dynmap.creating_labels = false;
                                } else {
                                    P_Dynmap.dyn_po_cnt = town_list.length;

                                    for (int i = 0; i < town_list.length; i++) {
                                        String[] addr_list = C_Arrays.addresses_list(town_list[i]);
                                        if (addr_list == null) {
                                            Util.cinform("Dynmap - Problem getting address array.");
                                        } else {
                                            for (int j = 0; j < addr_list.length; j++) {
                                                address_count++;
                                            }
                                        }
                                    }

                                    VA_postal.dyn_po_mrkr = new Marker[town_list.length + VA_Dispatcher.aux_slots];
                                    VA_postal.dyn_po_str = new String[town_list.length + VA_Dispatcher.aux_slots];
                                    int address_allocation = address_count + address_count * (VA_Dispatcher.aux_slots / 2);
                                    VA_postal.dyn_addr_mrkr = new Marker[address_allocation];
                                    VA_postal.dyn_addr_str_po = new String[address_allocation];
                                    VA_postal.dyn_addr_str_addr = new String[address_allocation];
                                    if (town_list == null) {
                                        Util.cinform("Dynmap - Problem getting town array.");
                                        P_Dynmap.creating_labels = false;
                                    } else {
                                        address_count = 0;

                                        for (int i = 0; i < town_list.length; i++) {
                                            String stown = town_list[i];
                                            String sowner = "server";
                                            if (C_Owner.is_local_po_owner_defined(stown)) {
                                                sowner = C_Owner.get_owner_local_po(stown);
                                            }

                                            String[] addr_list = C_Arrays.addresses_list(town_list[i]);
                                            if (addr_list != null) {
                                                Location location = Util.str2location(C_Postoffice.get_local_po_location_by_name(stown));
                                                String sid = null;
                                                String saddr_cnt = null;
                                                sid = "po_" + Util.int2str(i);
                                                saddr_cnt = Util.int2str(addr_list.length);
                                                String sworld = location.getWorld().getName();
                                                if (P_Dynmap.is_world_enabled(sworld)) {
                                                    double X = location.getX();
                                                    double Y = location.getY();
                                                    double Z = location.getZ();
                                                    if (VA_postal.dyn_po_mrkr[i] != null) {
                                                        VA_postal.dyn_po_mrkr[i].deleteMarker();
                                                        VA_postal.dyn_po_mrkr[i] = null;
                                                    }

                                                    String msg;
                                                    if (VA_postal.towny_configured && VA_postal.towny_opt_in && P_Towny.is_towny_by_db(stown)) {
                                                        String townt_t = P_Towny.get_town_name_by_db(stown);
                                                        String blocks = Util.int2str(P_Towny.get_town_blocks_by_db(stown));
                                                        msg = "Post office in "
                                                            + P_Dynmap.df(townt_t)
                                                            + ", named "
                                                            + P_Dynmap.df(stown)
                                                            + ", owned by "
                                                            + P_Dynmap.df(sowner)
                                                            + ", services "
                                                            + saddr_cnt
                                                            + " addresses."
                                                            + "  This town contains "
                                                            + blocks
                                                            + " town blocks.";
                                                    } else {
                                                        msg = "Post office for "
                                                            + P_Dynmap.df(stown)
                                                            + ", owned by "
                                                            + P_Dynmap.df(sowner)
                                                            + ", services "
                                                            + saddr_cnt
                                                            + " addresses.";
                                                    }

                                                    VA_postal.dyn_po_mrkr[i] = VA_postal.markerset
                                                        .createMarker(sid, P_Dynmap.df(stown), sworld, X, Y, Z, VA_postal.dyn_postoffice_ico, false);
                                                    VA_postal.dyn_po_mrkr[i].setDescription(msg);
                                                    VA_postal.dyn_po_str[i] = stown;
                                                    if (addr_list == null) {
                                                        Util.cinform("Dynmap - Problem getting address array.");
                                                    } else {
                                                        for (int j = 0; j < addr_list.length; j++) {
                                                            String saddr = addr_list[j];
                                                            sowner = "server";
                                                            if (C_Owner.is_address_owner_defined(stown, saddr)) {
                                                                sowner = C_Owner.get_owner_address(stown, saddr);
                                                            }

                                                            location = Util.str2location(C_Address.get_address_location(stown, saddr));
                                                            sid = "addr_" + Util.int2str(address_count);
                                                            sworld = location.getWorld().getName();
                                                            if (P_Dynmap.is_world_enabled(sworld)) {
                                                                X = location.getX();
                                                                Y = location.getY();
                                                                Z = location.getZ();
                                                                if (VA_postal.dyn_addr_mrkr[address_count] != null) {
                                                                    VA_postal.dyn_addr_mrkr[address_count].deleteMarker();
                                                                    VA_postal.dyn_addr_mrkr[address_count] = null;
                                                                }

                                                                if (VA_postal.towny_configured && VA_postal.towny_opt_in && P_Towny.is_towny_by_db(stown)) {
                                                                    String townt_t = P_Towny.get_town_name_by_db(stown);
                                                                    msg = "Address: "
                                                                        + P_Dynmap.df(saddr)
                                                                        + ", post office name: "
                                                                        + P_Dynmap.df(stown)
                                                                        + ", owned by: "
                                                                        + P_Dynmap.df(sowner)
                                                                        + ", in town: "
                                                                        + P_Dynmap.df(townt_t);
                                                                } else {
                                                                    msg = "Address: "
                                                                        + P_Dynmap.df(saddr)
                                                                        + ", in town: "
                                                                        + P_Dynmap.df(stown)
                                                                        + ", owned by: "
                                                                        + P_Dynmap.df(sowner);
                                                                }

                                                                VA_postal.dyn_addr_mrkr[address_count] = VA_postal.markerset
                                                                    .createMarker(sid, P_Dynmap.df(saddr), sworld, X, Y, Z, VA_postal.dyn_address_ico, false);
                                                                VA_postal.dyn_addr_mrkr[address_count].setDescription(msg);
                                                                VA_postal.dyn_addr_str_po[address_count] = stown;
                                                                VA_postal.dyn_addr_str_addr[address_count] = saddr;
                                                                address_count++;
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        P_Dynmap.dyn_addr_cnt = address_count;
                                        P_Dynmap.creating_labels = false;
                                    }
                                }
                            }
                        },
                        20L
                    );
            }
        }
    }

    public static void delete_static_labels() {
        if (VA_postal.dynmap_configured) {
            if (VA_postal.dyn_po_mrkr != null) {
                for (int i = 0; i < dyn_po_cnt; i++) {
                    if (VA_postal.dyn_po_mrkr[i] != null) {
                        VA_postal.dyn_po_mrkr[i].deleteMarker();
                        VA_postal.dyn_po_mrkr[i] = null;
                    }
                }
            }

            if (VA_postal.dyn_addr_mrkr != null) {
                for (int i = 0; i < dyn_addr_cnt; i++) {
                    if (VA_postal.dyn_addr_mrkr[i] != null) {
                        VA_postal.dyn_addr_mrkr[i].deleteMarker();
                        VA_postal.dyn_addr_mrkr[i] = null;
                    }
                }
            }

            dyn_po_cnt = 0;
            dyn_addr_cnt = 0;
        }
    }

    public static void create_marker_postmaster(String slocation, String label) {
        if (VA_postal.dynmap_configured) {
            if (VA_postal.dynmap_active) {
                if (VA_postal.dyn_postmaster_ico == null) {
                    VA_postal.dyn_postmaster_ico = VA_postal.markerapi.getMarkerIcon("world");
                }

                Location location = Util.str2location(slocation);
                String sid = "pm_1000";
                String sworld = location.getWorld().getName();
                if (!is_world_enabled(sworld)) {
                    return;
                }

                double X = location.getX();
                double Y = location.getY();
                double Z = location.getZ();
                if (VA_postal.dyn_postmaster != null) {
                    VA_postal.dyn_postmaster.deleteMarker();
                }

                VA_postal.dyn_postmaster = VA_postal.markerset.createMarker(sid, label, sworld, X, Y, Z, VA_postal.dyn_postmaster_ico, false);
            }
        }
    }

    public static void create_marker_postman(int id, String slocation, String label) {
        if (VA_postal.dynmap_configured) {
            if (VA_postal.markerset != null) {
                if (VA_postal.dynmap_active) {
                    Location location = Util.str2location(slocation);
                    String sid = "pm_" + Util.int2str(id);
                    String sworld = location.getWorld().getName();
                    if (!is_world_enabled(sworld)) {
                        return;
                    }

                    int radius = 2;
                    double X = location.getX();
                    double Y = location.getY();
                    double Z = location.getZ();
                    if (VA_postal.dyn_postman[id] != null) {
                        VA_postal.dyn_postman[id].deleteMarker();
                    }

                    VA_postal.dyn_postman[id] = VA_postal.markerset.createCircleMarker(sid, label, false, sworld, X, Y, Z, radius, radius, false);
                    VA_postal.dyn_postman_po[id] = label;
                }

                dyn_postmen_cnt++;
            }
        }
    }

    public static void delete_postmen() {
        if (VA_postal.dynmap_configured) {
            if (VA_postal.dynmap_active) {
                for (int i = 0; i < VA_postal.dyn_postman.length; i++) {
                    if (VA_postal.dyn_postman[i] != null) {
                        VA_postal.dyn_postman[i].setLineStyle(1, 0.1, black);
                        VA_postal.dyn_postman[i].setFillStyle(0.1, black);
                        VA_postal.dyn_postman[i].setRadius(1.0, 1.0);
                        VA_postal.dyn_postman_po[i] = null;
                    }
                }
            }

            dyn_postmen_cnt = 0;
        }
    }

    public static void show_route(final int id) {
        if (VA_postal.dynmap_configured) {
            if (VA_postal.markerset != null && VA_postal.wtr_poffice[id] != null && VA_postal.wtr_address[id] != null && VA_postal.wtr_waypoint[id] != null) {
                VA_postal.plugin
                    .getServer()
                    .getScheduler()
                    .runTaskLaterAsynchronously(
                        VA_postal.plugin,
                        new Runnable() {
                            @Override
                            public void run() {
                                if (VA_postal.dynmap_active && VA_Dispatcher.dispatcher_running) {
                                    int line_weight = 4;
                                    double line_opacity = 0.5;
                                    int line_color = P_Dynmap.red;
                                    String sworld = VA_postal.wtr_waypoint[id].getWorld().getName();
                                    if (P_Dynmap.is_world_enabled(sworld)) {
                                        String stown = VA_postal.wtr_poffice[id];
                                        String saddr = VA_postal.wtr_address[id];
                                        String sid = "rt_" + Util.int2str(id);
                                        String route_secs = C_Address.get_addr_interval(stown, saddr).trim();
                                        int waypoint_cnt = C_Route.route_waypoint_count(stown, saddr);
                                        double[] X = new double[waypoint_cnt];
                                        double[] Y = new double[waypoint_cnt];
                                        double[] Z = new double[waypoint_cnt];

                                        for (int i = 0; i < waypoint_cnt; i++) {
                                            String swaypoint = C_Route.get_waypoint_location(stown, saddr, i);
                                            Location waypoint = Util.str2location(swaypoint);
                                            if (waypoint == null) {
                                                break;
                                            }

                                            X[i] = waypoint.getX();
                                            Y[i] = waypoint.getY();
                                            Z[i] = waypoint.getZ();
                                        }

                                        if (X != null) {
                                            String msg = "Route from the "
                                                + P_Dynmap.df(stown)
                                                + " post office to the address: "
                                                + P_Dynmap.df(saddr)
                                                + ".  Last walk took "
                                                + route_secs
                                                + " seconds round trip.";
                                            if (VA_postal.dyn_route[id] == null) {
                                                VA_postal.dyn_route[id] = VA_postal.markerset.createPolyLineMarker(sid, msg, false, sworld, X, Y, Z, false);
                                            } else {
                                                VA_postal.dyn_route[id].setCornerLocations(X, Y, Z);
                                                VA_postal.dyn_route[id].setLabel(msg);
                                            }

                                            VA_postal.dyn_route[id].setLineStyle(line_weight, line_opacity, line_color);
                                        }
                                    }
                                }
                            }
                        },
                        5L
                    );
            }
        }
    }

    public static void hide_route(int id) {
        if (VA_postal.dynmap_configured) {
            if (VA_postal.dynmap_active && VA_Dispatcher.dispatcher_running) {
                if (VA_postal.dyn_route[id] != null) {
                    String sid = Util.int2str(id);
                    double[] X = new double[2];
                    double[] Y = new double[2];
                    double[] Z = new double[2];
                    X[0] = VA_postal.dyn_route[id].getCornerX(0);
                    Y[0] = VA_postal.dyn_route[id].getCornerY(0);
                    Z[0] = VA_postal.dyn_route[id].getCornerZ(0);
                    X[1] = X[0];
                    Y[1] = Y[0] + 1.0;
                    Z[1] = Z[0];
                    VA_postal.dyn_route[id].setCornerLocations(X, Y, Z);
                    VA_postal.dyn_route[id].setLineStyle(1, 0.1, 0);
                    VA_postal.dyn_route[id].setLabel("");
                }
            }
        }
    }

    public static void delete_routes() {
        if (VA_postal.dynmap_configured) {
            if (!deleting_routes) {
                deleting_routes = true;
                if (VA_postal.dynmap_active) {
                    double[] X = new double[2];
                    double[] Y = new double[2];
                    double[] Z = new double[2];

                    for (int i = 0; i < VA_postal.dyn_route.length; i++) {
                        if (VA_postal.dyn_route[i] != null) {
                            String sid = Util.int2str(i);
                            X[0] = VA_postal.dyn_route[i].getCornerX(0);
                            Y[0] = VA_postal.dyn_route[i].getCornerY(0);
                            Z[0] = VA_postal.dyn_route[i].getCornerZ(0);
                            X[1] = X[0];
                            Y[1] = Y[0] + 1.0;
                            Z[1] = Z[0];
                            VA_postal.dyn_route[i].setCornerLocations(X, Y, Z);
                            VA_postal.dyn_route[i].setLineStyle(1, 0.1, 0);
                            VA_postal.dyn_route[i].setLabel("");
                        }
                    }
                }

                deleting_routes = false;
            }
        }
    }

    private static void delayed_nulls(long delay) {
        if (VA_postal.dynmap_configured) {
            if (!running_delayed_nulls) {
                running_delayed_nulls = true;
                VA_postal.plugin.getServer().getScheduler().runTaskLaterAsynchronously(VA_postal.plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (VA_postal.dynmap_active && VA_Dispatcher.dispatcher_running) {
                            if (VA_postal.dyn_route != null) {
                                for (int i = 0; i < VA_postal.dyn_route.length; i++) {
                                    if (VA_postal.dyn_route[i] != null) {
                                        VA_postal.dyn_route[i].deleteMarker();
                                        VA_postal.dyn_route[i] = null;
                                    }
                                }
                            }

                            if (VA_postal.dyn_postman != null) {
                                for (int i = 0; i < VA_postal.dyn_postman.length; i++) {
                                    if (VA_postal.dyn_postman[i] != null) {
                                        VA_postal.dyn_postman[i].deleteMarker();
                                        VA_postal.dyn_postman[i] = null;
                                    }
                                }
                            }

                            if (VA_postal.dyn_postmaster != null) {
                                VA_postal.dyn_postmaster.deleteMarker();
                                VA_postal.dyn_postmaster = null;
                            }

                            P_Dynmap.running_delayed_nulls = false;
                        }
                    }
                }, delay);
            }
        }
    }

    public static void update_central_pos(String stown) {
        if (VA_postal.dynmap_configured) {
            if (VA_postal.dynmap_active) {
                Location target = Util.str2location(C_Postoffice.get_local_po_location_by_name(stown));
                String sworld = target.getWorld().getName();
                if (!is_world_enabled(sworld)) {
                    return;
                }

                double X = target.getX() - 4.0;
                double Y = target.getY() + 4.0;
                double Z = target.getZ();
                String pmaster_secs = C_Postoffice.get_po_interval(stown).trim();
                String msg = "The PostMaster is managing out of town mail at: " + df(stown) + ".  His last visit was " + pmaster_secs + " seconds ago.";
                if (VA_postal.dyn_postmaster != null) {
                    VA_postal.dyn_postmaster.setDescription(msg);
                    VA_postal.dyn_postmaster.setLocation(sworld, X, Y, Z);
                }
            }
        }
    }

    public static void update_pos(int id, boolean door, boolean residence, boolean finished) {
        if (VA_postal.dynmap_configured) {
            if (VA_postal.dynmap_active && VA_Dispatcher.dispatcher_running) {
                if (VA_postal.wtr_npc_player[id] != null
                    && VA_postal.wtr_poffice[id] != null
                    && VA_postal.wtr_address[id] != null
                    && VA_postal.dyn_postman[id] != null) {
                    Location loc = VA_postal.wtr_npc_player[id].getLocation();
                    String sworld = loc.getWorld().getName();
                    if (is_world_enabled(sworld)) {
                        double X = loc.getX();
                        double Y = loc.getY();
                        double Z = loc.getZ();
                        String stown = df(VA_postal.wtr_poffice[id]);
                        String saddr = df(VA_postal.wtr_address[id]);
                        String msg = "";
                        if (finished) {
                            VA_postal.dyn_postman[id].setLineStyle(1, 1.0, black);
                            VA_postal.dyn_postman[id].setFillStyle(0.5, cyan);
                            VA_postal.dyn_postman[id].setRadius(2.0, 2.0);
                            msg = "The postman for " + stown + " is waiting to start his next route.";
                            VA_postal.dyn_postman[id].setLabel(msg, false);
                            VA_postal.dyn_postman[id].setCenter(sworld, X, Y, Z);
                            hide_route(id);
                        } else if (door) {
                            VA_postal.dyn_postman[id].setLineStyle(1, 1.0, black);
                            VA_postal.dyn_postman[id].setFillStyle(0.5, yellow);
                            VA_postal.dyn_postman[id].setRadius(2.0, 2.0);
                            msg = "The postman for " + stown + " is navigating through a door.";
                            VA_postal.dyn_postman[id].setLabel(msg, false);
                            VA_postal.dyn_postman[id].setCenter(sworld, X, Y, Z);
                        } else if (residence) {
                            VA_postal.dyn_postman[id].setLineStyle(1, 1.0, black);
                            VA_postal.dyn_postman[id].setFillStyle(0.5, cyan);
                            VA_postal.dyn_postman[id].setRadius(2.0, 2.0);
                            msg = "The postman for " + stown + " has arrived at the address: " + saddr + ".";
                            VA_postal.dyn_postman[id].setLabel(msg, false);
                            VA_postal.dyn_postman[id].setCenter(sworld, X, Y, Z);
                        } else {
                            if (VA_postal.wtr_forward[id]) {
                                VA_postal.dyn_postman[id].setLineStyle(1, 1.0, black);
                                VA_postal.dyn_postman[id].setFillStyle(0.8, orange);
                                msg = "On route from the " + stown + " post office to the address: " + saddr + ".";
                            } else {
                                VA_postal.dyn_postman[id].setLineStyle(1, 1.0, black);
                                VA_postal.dyn_postman[id].setFillStyle(0.8, green);
                                msg = "Returning from the address: " + saddr + " to the post office: " + stown + ".";
                            }

                            double dist = VA_postal.wtr_waypoint[id].distance(loc);
                            VA_postal.dyn_postman[id].setRadius(2.0, 2.0);
                            VA_postal.dyn_postman[id].setLabel(msg, false);
                            VA_postal.dyn_postman[id].setCenter(sworld, X, Y, Z);
                            if (dist >= 7.0) {
                                incr_update(id);
                            }
                        }
                    }
                }
            }
        }
    }

    public static void incr_update(final int id) {
        if (VA_postal.dynmap_configured) {
            if (VA_postal.wtr_npc_player[id] != null && VA_postal.wtr_waypoint[id] != null && VA_postal.dyn_postman[id] != null) {
                VA_postal.plugin.getServer().getScheduler().scheduleSyncDelayedTask(VA_postal.plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (VA_postal.dynmap_active && VA_Dispatcher.dispatcher_running) {
                            Location loc = VA_postal.wtr_npc_player[id].getLocation();
                            String sworld = loc.getWorld().getName();
                            if (P_Dynmap.is_world_enabled(sworld)) {
                                double X = loc.getX();
                                double Y = loc.getY();
                                double Z = loc.getZ();
                                double dist = VA_postal.wtr_waypoint[id].distance(loc);
                                VA_postal.dyn_postman[id].setRadius(2.0, 2.0);
                                VA_postal.dyn_postman[id].setCenter(sworld, X, Y, Z);
                                if (dist >= 7.0) {
                                    P_Dynmap.incr_update(id);
                                }
                            }
                        }
                    }
                }, 7L);
            }
        }
    }

    public static String proper(String string) {
        try {
            if (string.length() > 0) {
                return string.substring(0, 1).toUpperCase() + string.substring(1).toLowerCase().trim();
            }
        } catch (Exception e) {
        }

        return "";
    }

    public static String df(String string) {
        try {
            String[] parts = string.split("_");
            String name = proper(parts[0]);
            if (parts.length > 1) {
                name = name + "_" + Util.proper(parts[1]);
            }

            if (parts.length > 2) {
                name = name + "_" + Util.proper(parts[2]);
            }

            if (parts.length > 3) {
                name = name + "_" + Util.proper(parts[3]);
            }

            return name;
        } catch (Exception e) {
            return "";
        }
    }

    public static String fixed_len(String input, int len, String filler) {
        try {
            input = input.trim();
            if (input.length() >= len) {
                return input.substring(0, len);
            }

            while (input.length() < len) {
                input = input + filler;
            }

            return input;
        } catch (Exception e) {
            String blank = "";

            for (int i = 0; i < len; i++) {
                blank = blank + filler;
            }

            return blank;
        }
    }
}
