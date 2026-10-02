package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.commands.Cmdexecutor;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Dispatcher;
import com.vodhanel.minecraft.va_postal.config.C_Queue;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.navigation.RouteMngr;
import org.bukkit.scheduler.BukkitTask;

public class VA_Dispatcher {
    public static long dispatcher_heartbeat = -1L;
    public static boolean dispatcher_async = false;
    public static boolean dispatcher_auto_cal = true;
    public static int dispatcher_id = -1;
    public static boolean dispatcher_running = false;
    public static BukkitTask dispatcher_task = null;
    public static int aux_slots = 10;
    public static long restart_cool = 200L;
    public static boolean restarting = false;
    public static boolean postmen_initialized = false;

    public static synchronized void dispatcher(Long delay, Long period, boolean check_status) {
        if (!check_status || !dispatcher_running) {
            dispatcher_running = true;
            if (dispatcher_async) {
                dispatcher_task = VA_postal.plugin.getServer().getScheduler().runTaskTimerAsynchronously(VA_postal.plugin, new Runnable() {
                    @Override
                    public void run() {
                        VA_Dispatcher.heart_beat();
                    }
                }, delay, period);
            } else {
                dispatcher_task = VA_postal.plugin.getServer().getScheduler().runTaskTimer(VA_postal.plugin, new Runnable() {
                    @Override
                    public void run() {
                        VA_Dispatcher.heart_beat();
                    }
                }, delay, period);
            }
        }
    }

    public static void heart_beat() {
        if (dispatcher_running) {
            dispatcher_id = dispatcher_task.getTaskId();
            int elapsed_seconds_since_last_npc_aciviy = Util.time_stamp() - VA_postal.wtr_watchdog_sys_ext_stamp;
            int wd_delay = VA_postal.wtr_postman_cool;
            if (wd_delay > 40) {
                wd_delay += 20;
            } else {
                wd_delay = 60;
            }

            if (elapsed_seconds_since_last_npc_aciviy > wd_delay) {
                VA_postal.wtr_watchdog_sys_ext_stamp = Util.time_stamp();
                Util.cinform("\u001b[1;31m[Dispatcher] Activity timeout for job queue.");
                Util.cinform("\u001b[0;33m[Dispatcher] Re-starting server.");
                restart(true);
            }

            String queue_pair = C_Queue.get_next_queue_task();
            if (!"BZY".equals(queue_pair.trim())) {
                RouteMngr.npc_scheduler(queue_pair);
            }

            Cmdexecutor.age_confirm_queue();
            VA_Ping.ping_central_postmaster();
            VA_Ping.ping_route_watchdog();
        }
    }

    public static synchronized void cancel_dispatcher(boolean quiet) {
        if (!restarting) {
            if (dispatcher_id >= 0) {
                restarting = true;
                dispatcher_running = false;
                if (VA_postal.dynmap_configured) {
                    P_Dynmap.dynmap_stop();
                }

                if (!quiet) {
                    Util.cinform("\u001b[1;31m[Stopping VA_postal] \u001b[0;37mDispatcher stopped.");
                }

                VA_postal.plugin.getServer().getScheduler().scheduleSyncDelayedTask(VA_postal.plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (VA_Dispatcher.dispatcher_id >= 0) {
                            VA_Dispatcher.dispatcher_task.cancel();
                            VA_postal.central_array_name = null;
                            VA_postal.central_array_location = null;
                            VA_postal.central_array_time = null;
                            RouteMngr.npc_delete_all(true);
                            VA_postal.central_route_pos = 0;
                            VA_postal.route_watchdog_ping_time = -1L;
                            VA_postal.route_watchdog_pos = 0;
                            VA_Arrays.clear_nav_and_goals();
                        }

                        VA_Dispatcher.restarting = false;
                    }
                }, restart_cool);
            }
        }
    }

    public static synchronized void start_up(boolean quiet) {
        aux_slots = 10;
        String[] town_list = C_Arrays.town_list();
        if (town_list != null && town_list.length > 0) {
            VA_Arrays.init_wtr_arrays(town_list.length + aux_slots);
            C_Dispatcher.reality_check_n_chunk_list();
            if (!VA_postal.needs_configuration) {
                C_Dispatcher.load_central_route_array(town_list);
                String[] var2 = null;
                VA_Timers.load_Static_Chunk_Regions();
                C_Dispatcher.create_dispatcher();
                P_Economy.verify_central();
                VA_postal.wtr_speed = GetConfig.speed();
                VA_postal.central_po_log_book_check = false;
                VA_postal.wtr_count = 0;
                postmen_initialized = false;
                VA_postal.wtr_postman_cool = 10;
                VA_postal.central_cooldown = 10;
                VA_postal.central_route_time = Util.time_stamp() + 20;
                VA_postal.wtr_watchdog_sys_ext_stamp = Util.time_stamp();
                dispatcher_heartbeat = GetConfig.heartbeat_ticks();
                dispatcher_async = GetConfig.heart_beat_async();
                dispatcher_auto_cal = GetConfig.heart_beat_auto();
                dispatcher(100L, dispatcher_heartbeat, true);
                if (VA_postal.dynmap_configured) {
                    P_Dynmap.dynmap_start();
                }

                if (!quiet) {
                    Util.con_type("\u001b[0;32mVA_Postal started. \u001b[0;37m'postal talk'\u001b[0;32m to see activity.");
                }
            }
        } else {
            Util.con_type("\u001b[1;31mVA_Postal start aborted - could not compile town list.");
        }
    }

    public static synchronized void restart(final boolean quiet) {
        if (!restarting) {
            cancel_dispatcher(quiet);
            long delay = restart_cool + 20L;
            VA_postal.plugin.getServer().getScheduler().scheduleSyncDelayedTask(VA_postal.plugin, new Runnable() {
                @Override
                public void run() {
                    VA_Dispatcher.start_up(quiet);
                }
            }, delay);
        }
    }
}
