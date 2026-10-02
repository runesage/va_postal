package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.config.C_Dispatcher;
import com.vodhanel.minecraft.va_postal.listeners.RouteEditor;
import com.vodhanel.minecraft.va_postal.navigation.RouteMngr;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class VA_Timers {
    public static int new_mail_stamp = 0;

    public static synchronized void run_goal(final int id, Long delay) {
        VA_postal.wtr_cooling[id] = true;
        VA_postal.wtr_ext_watchdog_reset[id] = false;
        RouteMngr.set_range_and_speed(id);
        VA_postal.plugin
            .getServer()
            .getScheduler()
            .runTaskLater(
                VA_postal.plugin,
                new Runnable() {
                    @Override
                    public void run() {
                        if (VA_Dispatcher.dispatcher_running && VA_postal.wtr_controller[id] != null) {
                            String direction = "F";
                            if (!VA_postal.wtr_forward[id]) {
                                direction = "R";
                            }

                            if (VA_postal.queuetalk) {
                                Util.cinform(
                                    "\u001b[0;36m["
                                        + VA_postal.wtr_qpair[id]
                                        + "] "
                                        + "\u001b[0;37m"
                                        + "["
                                        + VA_postal.wtr_pos[id]
                                        + "], "
                                        + "\u001b[0;33m"
                                        + VA_postal.wtr_poffice[id]
                                        + ", "
                                        + VA_postal.wtr_address[id]
                                        + ", "
                                        + "\u001b[0;32m"
                                        + id
                                        + ", "
                                        + "\u001b[0;33m"
                                        + "("
                                        + VA_postal.wtr_swaypoint[id]
                                        + ") "
                                        + "\u001b[0;32m"
                                        + direction
                                );
                            }

                            VA_Timers.submit_goal(id);
                            VA_Timers.close_chest(id);
                            VA_postal.wtr_cooling[id] = false;
                        }
                    }
                },
                delay
            );
    }

    public static synchronized void submit_goal(int id) {
        VA_postal.wtr_not_postal_fired[id] = true;
        VA_postal.wtr_waypoint_completed[id] = false;
        VA_postal.wtr_controller[id].clear();
        VA_postal.wtr_controller[id].addGoal(VA_postal.wtr_goal[id], 10);
        VA_postal.wtr_controller[id].run();
        if (VA_postal.wtr_controller[id] != null && VA_postal.wtr_controller[id].isPaused()) {
            VA_postal.wtr_controller[id].setPaused(false);
        }
    }

    public static synchronized void close_chest(int id) {
        if (VA_postal.wtr_chest_open[id]) {
            try {
                VA_postal.wtr_npc_player[id] = (Player)VA_postal.wtr_npc[id].getBukkitEntity();
                VA_postal.wtr_npc_player[id].closeInventory();
            } catch (Exception e) {
            }

            VA_postal.wtr_chest_open[id] = false;
        }
    }

    public static synchronized void load_Static_Chunk_Regions() {
        VA_postal.plugin.getServer().getScheduler().scheduleSyncDelayedTask(VA_postal.plugin, new Runnable() {
            @Override
            public void run() {
                if (VA_Dispatcher.dispatcher_running) {
                    C_Dispatcher.load_static_regions();
                }
            }
        }, 50L);
    }

    public static synchronized void routeRditor_start(boolean start, Player player) {
        if (start) {
            if (player != null) {
                VA_postal.plistener_player = player;
                VA_postal.plistener_last_used = Util.time_stamp();
                VA_postal.plistener_cooling = false;
            }
        } else {
            VA_postal.plistener_cooling = true;
            routeRditor_cooldown(player);
        }
    }

    public static synchronized void routeRditor_cooldown(final Player player) {
        VA_postal.plugin.getServer().getScheduler().scheduleSyncDelayedTask(VA_postal.plugin, new Runnable() {
            @Override
            public void run() {
                VA_postal.plistener_player = null;
                VA_postal.plistener_last_used = -1;
                VA_postal.plistener_cooling = false;
                if (player != null) {
                    Util.pinform(player, "&e&oDone.");
                }
            }
        }, 10L);
    }

    public static synchronized void hideroute(String stown, String saddress) {
        VA_postal.plugin.getServer().getScheduler().scheduleSyncDelayedTask(VA_postal.plugin, new Runnable() {
            @Override
            public void run() {
                if (VA_postal.plistener_player == null) {
                    RouteEditor.clear_route_markers();
                }
            }
        }, 1000L);
    }

    public static void report_newmail_all() {
        int interval_secs = VA_postal.check_new_mail_secs;
        if (interval_secs >= 30) {
            int interval = Util.time_stamp() - new_mail_stamp;
            if (interval_secs != 0 && interval >= interval_secs) {
                int size = VA_postal.plugin.getServer().getMaxPlayers();
                Player[] plist = new Player[size];
                int i = 0;

                for (Player itr_player : VA_postal.plugin.getServer().getOnlinePlayers()) {
                    plist[i] = itr_player;
                    i++;
                }

                final Player[] f_plist = plist;
                Bukkit.getServer().getScheduler().runTaskLaterAsynchronously(VA_postal.plugin, new Runnable() {
                    @Override
                    public void run() {
                        for (Player itr_plist : f_plist) {
                            if (itr_plist == null) {
                                break;
                            }

                            if (itr_plist.isValid() && itr_plist.isOnline()) {
                                Util.list_newmail(itr_plist);
                            }

                            try {
                                Thread.sleep(1500L);
                            } catch (InterruptedException e) {
                            }
                        }

                        VA_Timers.new_mail_stamp = Util.time_stamp();
                    }
                }, 10L);
            }
        }
    }
}
