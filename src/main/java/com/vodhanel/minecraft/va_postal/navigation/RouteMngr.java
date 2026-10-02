package com.vodhanel.minecraft.va_postal.navigation;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Dynmap;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.common.VA_Dispatcher;
import com.vodhanel.minecraft.va_postal.common.VA_Timers;
import com.vodhanel.minecraft.va_postal.config.C_Citizens;
import com.vodhanel.minecraft.va_postal.config.C_Dispatcher;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.C_Queue;
import com.vodhanel.minecraft.va_postal.config.C_Route;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.trait.LookClose;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class RouteMngr {
    private static int cal_cos_threshold = 5;
    private static int[] cal_history = new int[5];
    private static int cal_history_pos = 0;
    private static int cal_cosecutive = 0;
    private static double cal_adjuster = 1.05;
    private static int cal_stability = 0;
    private static long cal_last_save = 0L;
    public static boolean cit_new_nav = false;
    public static double cit_distanceMargin = 0.05;
    public static boolean cit_avoidWater = false;
    public static boolean cit_ground_waypoint = false;
    public static int cit_stationaryTicks = 20;
    public static float cit_range = 100.0F;

    public static synchronized int npc_create(String slocation, boolean local, String label) {
        int id = -1;
        String name = "PostMan";
        if (local) {
            name = GetConfig.get_local_pman_name();
        } else {
            name = GetConfig.get_central_pman_name();
        }

        Util.dinform("\u001b[1;34mnpc_create: " + slocation + "," + name);
        Location location = Util.str2location(slocation);
        boolean used_deleted_slot = false;
        if (local) {
            try {
                id = VA_postal.wtr_count;

                for (int i = 0; i < VA_postal.wtr_count; i++) {
                    if (VA_postal.wtr_npc[i] == null) {
                        id = i;
                        used_deleted_slot = true;
                        break;
                    }
                }

                EntityType buk_entity_type = EntityType.PLAYER;
                VA_postal.wtr_npc[id] = VA_postal.npcRegistry.createNPC(buk_entity_type, name);
                VA_postal.wtr_slocation_local_po_spawn[id] = slocation;
                VA_postal.wtr_npc[id].spawn(location);
                VA_postal.wtr_npc_player[id] = (Player)VA_postal.wtr_npc[id].getBukkitEntity();
                VA_postal.wtr_inventory_npc[id] = VA_postal.wtr_npc_player[id].getInventory();
                VA_postal.wtr_watchdog_ext_npc_stamp[id] = Util.time_stamp();
                initialize_npc(id);
            } catch (Exception e) {
                Util.cinform("\u001b[0;33mException creating NPC: \u001b[0;37m" + id + ", retrying...");
                return -1;
            }

            if (VA_postal.dynmap_configured) {
                P_Dynmap.create_marker_postman(id, slocation, label);
            }

            if (!used_deleted_slot) {
                VA_postal.wtr_count++;
            }
        } else {
            try {
                id = 1000;
                VA_postal.central_route_npc_reg = CitizensAPI.getNPCRegistry();
                EntityType buk_entity_type = EntityType.PLAYER;
                VA_postal.central_route_npc = VA_postal.central_route_npc_reg.createNPC(buk_entity_type, name);
                VA_postal.central_po_slocation_spawn = slocation;
                VA_postal.central_route_npc.spawn(location);
                VA_postal.central_route_player = (Player)VA_postal.central_route_npc.getBukkitEntity();
                initialize_npc(id);
            } catch (Exception e) {
                Util.cinform("\u001b[0;33mException creating Central NPC:, retrying... ");
                return -1;
            }

            if (VA_postal.dynmap_configured) {
                P_Dynmap.create_marker_postmaster(slocation, label);
            }
        }

        return id;
    }

    public static synchronized void initialize_npc(int id) {
        EntityType buk_entity_type = EntityType.PLAYER;
        if (id == 1000) {
            if (VA_postal.central_route_npc == null || VA_postal.central_route_npc.getBukkitEntity() == null) {
                return;
            }

            if (!VA_postal.central_route_npc.isSpawned()) {
                Location target = VA_postal.central_route_npc.getBukkitEntity().getLocation();
                VA_postal.central_route_npc.spawn(target);
            }

            ((Owner)VA_postal.central_route_npc.getTrait(Owner.class)).setOwner("server");
            ((MobType)VA_postal.central_route_npc.getTrait(MobType.class)).setType(buk_entity_type);
            ((LookClose)VA_postal.central_route_npc.getTrait(LookClose.class)).lookClose(true);
            Equipment trait = (Equipment)VA_postal.central_route_npc.getTrait(Equipment.class);
            ItemStack uniform = uniform_part(1, false);
            if (uniform != null && trait != null) {
                trait.set(1, uniform);
            }

            uniform = uniform_part(2, false);
            if (uniform != null && trait != null) {
                trait.set(2, uniform);
            }

            uniform = uniform_part(3, false);
            if (uniform != null && trait != null) {
                trait.set(3, uniform);
            }

            uniform = uniform_part(4, false);
            if (uniform != null && trait != null) {
                trait.set(4, uniform);
            }
        } else {
            if (VA_postal.wtr_npc[id] == null || VA_postal.wtr_npc[id].getBukkitEntity() == null) {
                return;
            }

            if (!VA_postal.wtr_npc[id].getBukkitEntity().isValid()) {
                Location target = Util.str2location(VA_postal.wtr_slocation_local_po_spawn[id]);
                VA_postal.wtr_npc[id].despawn();
                VA_postal.wtr_npc[id].spawn(target);
                VA_postal.wtr_npc_player[id] = (Player)VA_postal.wtr_npc[id].getBukkitEntity();
                VA_postal.wtr_inventory_npc[id] = VA_postal.wtr_npc_player[id].getInventory();
                Object var6 = null;
            }

            if (!VA_postal.wtr_npc[id].isSpawned()) {
                Location target = VA_postal.wtr_npc[id].getBukkitEntity().getLocation();
                VA_postal.wtr_npc[id].spawn(target);
            }

            VA_postal.wtr_inventory_npc[id].clear();
            VA_postal.wtr_npc_player[id] = (Player)VA_postal.wtr_npc[id].getBukkitEntity();
            VA_postal.wtr_npc_player[id].setItemInHand(null);
            ((Owner)VA_postal.wtr_npc[id].getTrait(Owner.class)).setOwner("server");
            ((MobType)VA_postal.wtr_npc[id].getTrait(MobType.class)).setType(buk_entity_type);
            ((LookClose)VA_postal.wtr_npc[id].getTrait(LookClose.class)).lookClose(true);
            Equipment trait = (Equipment)VA_postal.wtr_npc[id].getTrait(Equipment.class);
            ItemStack uniform = uniform_part(1, true);
            if (uniform != null && trait != null) {
                trait.set(1, uniform);
            }

            uniform = uniform_part(2, true);
            if (uniform != null && trait != null) {
                trait.set(2, uniform);
            }

            uniform = uniform_part(3, true);
            if (uniform != null && trait != null) {
                trait.set(3, uniform);
            }

            uniform = uniform_part(4, true);
            if (uniform != null && trait != null) {
                trait.set(4, uniform);
            }
        }
    }

    public static synchronized void lookclose_on_route(int id, boolean route_start) {
        if (route_start) {
            if (VA_postal.lookclose_on_route) {
                ((LookClose)VA_postal.wtr_npc[id].getTrait(LookClose.class)).lookClose(true);
            } else {
                ((LookClose)VA_postal.wtr_npc[id].getTrait(LookClose.class)).lookClose(false);
            }
        } else {
            ((LookClose)VA_postal.wtr_npc[id].getTrait(LookClose.class)).lookClose(true);
        }
    }

    public static synchronized boolean npc_delete_all(boolean quiet) {
        boolean result = true;

        for (int i = 0; i < VA_postal.wtr_count; i++) {
            if (VA_postal.wtr_npc[i] != null) {
                delete_npc(i);
            }
        }

        String postman = GetConfig.get_local_pman_name();
        String pmaster = GetConfig.get_central_pman_name();
        int index = 0;

        while (true) {
            NPC npc = null;

            try {
                npc = VA_postal.npcRegistry.getById(index);
            } catch (Exception e) {
                VA_postal.central_route_npc = null;
                VA_postal.central_route_player = null;
                VA_postal.wtr_npc = null;
                VA_postal.wtr_npc_player = null;
                VA_postal.wtr_count = 0;
                VA_postal.central_route_count = 0;
                return result;
            }

            try {
                if (npc != null) {
                    if (npc.getName().equals(postman)) {
                        npc.destroy();
                    }

                    if (npc.getName().equals(pmaster)) {
                        npc.destroy();
                    }
                }
            } catch (Exception e) {
            }

            index++;
        }
    }

    public static synchronized void delete_npc(int id) {
        if (VA_postal.wtr_nav[id] != null) {
            VA_postal.wtr_nav[id] = null;
        }

        if (VA_postal.wtr_goal[id] != null) {
            VA_postal.wtr_goal[id] = null;
        }

        if (VA_postal.wtr_goalselector[id] != null) {
            VA_postal.wtr_goalselector[id] = null;
        }

        if (VA_postal.wtr_controller[id] != null) {
            VA_postal.wtr_controller[id] = null;
        }

        if (VA_postal.wtr_npc_player[id] != null) {
            VA_postal.wtr_npc_player[id] = null;
        }

        if (VA_postal.wtr_npc[id] != null) {
            try {
                VA_postal.wtr_npc[id].destroy();
            } catch (Exception e) {
            }

            VA_postal.wtr_npc[id] = null;
        }

        if (VA_postal.wtr_poffice[id] != null) {
            VA_postal.wtr_poffice[id] = null;
        }

        if (VA_postal.wtr_address[id] != null) {
            VA_postal.wtr_address[id] = null;
        }
    }

    public static synchronized void npc_start_route(int id, String local_po, String address, String queue_pair) {
        if (VA_Dispatcher.dispatcher_running && C_Queue.is_queue_open(queue_pair)) {
            if (!C_Route.is_waypoint_defined(local_po, address, 1)) {
                C_Dispatcher.open_address(local_po, address, false);
            } else {
                VA_postal.wtr_last_stuck_action[id] = "";
                VA_postal.wtr_last_stuck_stamp[id] = System.currentTimeMillis();
                VA_postal.wtr_watchdog_stuck_stamp[id] = System.currentTimeMillis();
                VA_postal.wtr_watchdog_stuck_retry[id] = 0;
                VA_postal.wtr_watchdog_stuck_ms[id] = 10000;
                if (VA_postal.routetalk) {
                    Util.cinform("\u001b[0;32mPostMan: \u001b[0;37m" + local_po + ", " + address);
                }

                VA_postal.postal_route_stopping = false;
                VA_postal.postal_route_stopped = false;
                VA_postal.wtr_poffice[id] = local_po;
                VA_postal.wtr_address[id] = address;
                VA_postal.wtr_qpair[id] = queue_pair;
                VA_postal.wtr_done[id] = false;
                VA_postal.wtr_pos_last[id] = -1;
                VA_postal.wtr_pos[id] = 0;
                VA_postal.wtr_pos_next[id] = 1;
                VA_postal.wtr_pos_final[id] = C_Route.get_last_waypoint_position(local_po, address);
                VA_postal.wtr_swaypoint[id] = C_Route.get_waypoint_location(local_po, address, 0);
                VA_postal.wtr_waypoint[id] = Util.str2location(VA_postal.wtr_swaypoint[id]);
                VA_postal.wtr_id = C_Queue.npc_id_for_queue_pair(queue_pair);
                VA_postal.wtr_trap_door[id] = null;
                VA_postal.wtr_door_speed[id] = 1.0F;
                VA_postal.wtr_door[id] = false;
                VA_postal.wtr_sdoor_location[id] = "null";
                VA_postal.wtr_arriving[id] = false;
                VA_postal.wtr_arrived[id] = false;
                VA_postal.wtr_forward[id] = true;
                VA_postal.wtr_controller[id] = VA_postal.wtr_npc[VA_postal.wtr_id].getDefaultGoalController();
                VA_postal.wtr_swaypoint_next[id] = C_Route.get_waypoint_location(local_po, address, 1);
                VA_postal.wtr_waypoint_next[id] = Util.str2location(VA_postal.wtr_swaypoint_next[id]);
                VA_postal.wtr_dist_next[id] = VA_postal.wtr_waypoint[id].distance(VA_postal.wtr_waypoint_next[id]);
                VA_postal.wtr_waypoint_dynamic[id] = "null";
                VA_postal.wtr_goal[id] = new Goal_WTR(id);
                VA_postal.wtr_postal_route_start[id] = true;
                VA_postal.wtr_goal_active[id] = true;
                initialize_npc(id);
                dynamic_navigation(id);
                VA_postal.wtr_speed_factor[id] = 0.5F;
                VA_Timers.run_goal(id, 1L);
                if (VA_postal.dynmap_configured) {
                    P_Dynmap.show_route(id);
                }

                lookclose_on_route(id, true);
                C_Queue.queue_pair_activity_flag(queue_pair, true, true, false);
                VA_postal.wtr_watchdog_ext_last_location[id] = VA_postal.wtr_npc_player[id].getLocation();
            }
        }
    }

    public static synchronized void npc_next_waypoint(int id) {
        if (ID_WTR.npc_should_run(id)) {
            VA_postal.wtr_watchdog_ext_npc_stamp[id] = Util.time_stamp();
            VA_postal.wtr_watchdog_ext_last_location[id] = VA_postal.wtr_npc_player[id].getLocation();
            VA_postal.wtr_watchdog_stuck_stamp[id] = System.currentTimeMillis();
            VA_postal.wtr_watchdog_stuck_retry[id] = 0;
            if (!C_Route.is_waypoint_defined(VA_postal.wtr_poffice[id], VA_postal.wtr_address[id], VA_postal.wtr_pos_final[id])) {
                C_Dispatcher.open_address(VA_postal.wtr_poffice[id], VA_postal.wtr_address[id], false);
                cancel_route(id);
                Util.cinform("\u001b[0;33mMissing waypoint recovery.  Is someone route editing?");
                Util.cinform("\u001b[0;33m" + VA_postal.wtr_poffice[id] + ", " + VA_postal.wtr_address[id] + ", " + VA_postal.wtr_pos[id]);
            }

            if (!VA_postal.wtr_done[id]) {
                dynamic_navigation(id);
                if (VA_postal.wtr_door_nav[id] && !VA_postal.wtr_door_nav_enter[id]) {
                    ID_WTR.door_navigator(id, true);
                    VA_postal.wtr_speed_factor[id] = 0.5F;
                    VA_Timers.run_goal(id, 50L);
                    if (VA_postal.dynmap_configured) {
                        P_Dynmap.update_pos(id, true, false, false);
                    }
                } else if (VA_postal.wtr_door_nav[id] && VA_postal.wtr_door_nav_enter[id]) {
                    ID_WTR.door_navigator(id, false);
                    VA_postal.wtr_speed_factor[id] = 0.5F;
                    VA_Timers.run_goal(id, 120L);
                } else if (VA_postal.wtr_arriving[id] && !VA_postal.wtr_arrived[id]) {
                    Util.dinform("\u001b[1;34mArrived at address, cooling down...." + VA_postal.wtr_poffice[id] + " " + VA_postal.wtr_swaypoint[id]);
                    long pause = GetConfig.residence_cool_ticks();
                    VA_postal.wtr_speed_factor[id] = 0.5F;
                    VA_Timers.run_goal(id, pause);
                    if (VA_postal.dynmap_configured) {
                        P_Dynmap.update_pos(id, false, true, false);
                    }

                    C_Dispatcher.reset_pro_de_motion(VA_postal.wtr_qpair[id]);
                } else {
                    Util.dinform("\u001b[1;34mProceeding to next waypoint...." + VA_postal.wtr_poffice[id] + " " + VA_postal.wtr_swaypoint[id]);
                    VA_Timers.run_goal(id, 1L);
                    if (VA_postal.dynmap_configured) {
                        P_Dynmap.update_pos(id, false, false, false);
                    }
                }
            } else {
                lookclose_on_route(id, false);
                VA_postal.wtr_goal_active[id] = false;
                C_Dispatcher.reset_pro_de_motion(VA_postal.wtr_qpair[id]);
                C_Queue.queue_pair_activity_flag(VA_postal.wtr_qpair[id], false, true, true);
                if (VA_postal.dynmap_configured) {
                    P_Dynmap.update_pos(id, false, false, true);
                }
            }
        }
    }

    public static synchronized void cancel_route(int id) {
        if (VA_Dispatcher.dispatcher_running && VA_postal.wtr_goal_active[id]) {
            VA_postal.wtr_done[id] = true;
            String slocation = VA_postal.wtr_slocation_local_po_spawn[id];
            Location target = Util.str2location(slocation);

            try {
                VA_postal.wtr_npc[id].despawn();
                VA_postal.wtr_npc[id].spawn(target);
                VA_postal.wtr_npc_player[id] = (Player)VA_postal.wtr_npc[id].getBukkitEntity();
                VA_postal.wtr_inventory_npc[id] = VA_postal.wtr_npc_player[id].getInventory();
            } catch (Exception e) {
                Util.cinform("\u001b[1;31mProblem teleporting (cancel route)");
            }
        }
    }

    public static void npc_scheduler(final String queue_pair) {
        if (VA_Dispatcher.dispatcher_running && C_Queue.is_queue_open(queue_pair)) {
            if (VA_Dispatcher.dispatcher_async) {
                VA_postal.plugin.getServer().getScheduler().scheduleSyncDelayedTask(VA_postal.plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (VA_Dispatcher.dispatcher_running) {
                            if (C_Queue.is_queue_open(queue_pair)) {
                                RouteMngr.npc_scheduler_worker(queue_pair);
                            }
                        }
                    }
                }, 10L);
            } else {
                npc_scheduler_worker(queue_pair);
            }
        }
    }

    public static synchronized void npc_scheduler_worker(String queue_pair) {
        if (VA_Dispatcher.dispatcher_running && C_Queue.is_queue_open(queue_pair)) {
            if (VA_postal.plistener_player != null) {
                String stown = VA_postal.plistener_local_po;
                String saddress = VA_postal.plistener_address;
                String e_qp = C_Queue.get_queue_pair(stown, saddress);
                if (e_qp.equals(queue_pair)) {
                    C_Dispatcher.demote_schedule(stown, saddress, 3000);
                    return;
                }
            }

            int elapsed = -1;
            int cooldown = VA_postal.wtr_postman_cool;
            long age = C_Queue.queue_pair_get_age(queue_pair);
            long time = System.currentTimeMillis() / 1000L;
            elapsed = (int)(time - age);
            if (!C_Queue.is_task_promoted(queue_pair) && elapsed < cooldown) {
                int wait = cooldown - elapsed;
                Util.dinform("\u001b[0;37mWaiting " + wait + " seconds before running queue pair: " + queue_pair);
            } else {
                if (VA_Dispatcher.dispatcher_auto_cal) {
                    calibrate(cooldown, elapsed);
                }

                int id = -1;
                String spostoffice = C_Queue.queue_pair_get_town(queue_pair);
                String address = C_Queue.queue_pair_get_address(queue_pair);
                if (!C_Queue.npc_exist_for_queue_pair(queue_pair)) {
                    String slocation = C_Postoffice.get_local_po_location_by_name(spostoffice);
                    id = npc_create(slocation, true, spostoffice);
                    if (id < 0) {
                        Util.dinform("\u001b[0;33mRecieved bad id trying to create a npc, retrying...");
                        return;
                    }

                    C_Queue.update_npc_id_for_queue_pair(queue_pair, id);
                } else {
                    id = C_Queue.npc_id_for_queue_pair(queue_pair);
                }

                npc_start_route(id, spostoffice, address, queue_pair);
            }
        }
    }

    public static synchronized void calibrate(int cooldown, int actual_sec) {
        if (VA_Dispatcher.dispatcher_running) {
            if (cal_cos_threshold != 100) {
                if (cal_history_pos < cal_history.length) {
                    cal_history[cal_history_pos] = actual_sec;
                    cal_history_pos++;
                } else {
                    int average = 0;

                    for (int aCal_history : cal_history) {
                        average += aCal_history;
                    }

                    average /= cal_history.length;
                    int comp = cooldown + 5;
                    boolean reset = false;
                    if (comp < average) {
                        double adjuster = VA_Dispatcher.dispatcher_heartbeat;
                        VA_Dispatcher.dispatcher_heartbeat = (long)(adjuster * 0.9);
                        cal_stability = 0;
                        reset = true;
                    }

                    if (cal_cosecutive > 5) {
                        double adjuster = VA_Dispatcher.dispatcher_heartbeat;
                        VA_Dispatcher.dispatcher_heartbeat = (long)(adjuster * 1.05);
                        reset = true;
                    }

                    if (VA_Dispatcher.dispatcher_heartbeat > 200L) {
                        VA_Dispatcher.dispatcher_heartbeat = 200L;
                    }

                    if (VA_Dispatcher.dispatcher_heartbeat < 30L) {
                        VA_Dispatcher.dispatcher_heartbeat = 305L;
                    }

                    if (reset) {
                        VA_postal.plugin.getServer().getScheduler().cancelTask(VA_Dispatcher.dispatcher_id);
                        VA_Dispatcher.dispatcher(VA_Dispatcher.dispatcher_heartbeat, VA_Dispatcher.dispatcher_heartbeat, false);
                        cal_cosecutive = 0;
                    }

                    if (cal_stability > cal_cos_threshold) {
                        if (cal_last_save != VA_Dispatcher.dispatcher_heartbeat) {
                            GetConfig.set_heartbeat_ticks(VA_Dispatcher.dispatcher_heartbeat);
                            cal_last_save = VA_Dispatcher.dispatcher_heartbeat;
                            Util.cinform(
                                "\u001b[0;36mPostal auto calibration saved.  Heartbeat: "
                                    + VA_Dispatcher.dispatcher_heartbeat
                                    + ", Stability: "
                                    + cal_cos_threshold
                            );
                        }

                        switch (cal_cos_threshold) {
                            case 5:
                                cal_cos_threshold = 7;
                                cal_adjuster = 1.04;
                                break;
                            case 6:
                            case 8:
                            case 9:
                            case 11:
                            case 12:
                            default:
                                cal_cos_threshold = 100;
                                Util.cinform(
                                    "\u001b[0;36mPostal final calibration saved.  Heartbeat: "
                                        + VA_Dispatcher.dispatcher_heartbeat
                                        + ", Stability: "
                                        + cal_cos_threshold
                                );
                                break;
                            case 7:
                                cal_cos_threshold = 10;
                                cal_adjuster = 1.03;
                                break;
                            case 10:
                                cal_cos_threshold = 13;
                                cal_adjuster = 1.02;
                                break;
                            case 13:
                                cal_cos_threshold = 15;
                                cal_adjuster = 1.01;
                        }

                        cal_stability = 0;
                    }

                    cal_stability++;
                    cal_cosecutive++;
                    cal_history_pos = 0;
                }
            }
        }
    }

    private static synchronized ItemStack uniform_part(int slot, boolean local) {
        switch (slot) {
            case 1:
                int helmet = GetConfig.uniform_part_config(slot, local);
                switch (helmet) {
                    case 298:
                        return new ItemStack(helmet, 1);
                    case 302:
                        return new ItemStack(helmet, 1);
                    case 306:
                        return new ItemStack(helmet, 1);
                    case 310:
                        return new ItemStack(helmet, 1);
                    case 314:
                        return new ItemStack(helmet, 1);
                    default:
                        return null;
                }
            case 2:
                int chestplate = GetConfig.uniform_part_config(slot, local);
                switch (chestplate) {
                    case 299:
                        return new ItemStack(chestplate, 1);
                    case 303:
                        return new ItemStack(chestplate, 1);
                    case 307:
                        return new ItemStack(chestplate, 1);
                    case 311:
                        return new ItemStack(chestplate, 1);
                    case 315:
                        return new ItemStack(chestplate, 1);
                    default:
                        return null;
                }
            case 3:
                int leggings = GetConfig.uniform_part_config(slot, local);
                switch (leggings) {
                    case 300:
                        return new ItemStack(leggings, 1);
                    case 304:
                        return new ItemStack(leggings, 1);
                    case 308:
                        return new ItemStack(leggings, 1);
                    case 312:
                        return new ItemStack(leggings, 1);
                    case 316:
                        return new ItemStack(leggings, 1);
                    default:
                        return null;
                }
            case 4:
                int boots = GetConfig.uniform_part_config(slot, local);
                switch (boots) {
                    case 301:
                        return new ItemStack(boots, 1);
                    case 305:
                        return new ItemStack(boots, 1);
                    case 309:
                        return new ItemStack(boots, 1);
                    case 313:
                        return new ItemStack(boots, 1);
                    case 317:
                        return new ItemStack(boots, 1);
                    default:
                        return null;
                }
            default:
                return null;
        }
    }

    public static synchronized void set_range_and_speed(int id) {
        if (cit_range < 0.0F) {
            if (VA_postal.wtr_range[id] < 25.0F) {
                VA_postal.wtr_range[id] = 25.0F;
            }

            VA_postal.wtr_nav[id].getDefaultParameters().range(VA_postal.wtr_range[id]);
        }

        if (VA_postal.wtr_speed_factor[id] < 0.5F) {
            VA_postal.wtr_speed_factor[id] = 0.5F;
        }

        VA_postal.wtr_nav[id].getDefaultParameters().speedModifier(VA_postal.wtr_speed_factor[id]);
    }

    public static synchronized void dynamic_navigation(int id) {
        if (VA_Dispatcher.dispatcher_running && VA_postal.wtr_goal_active[id]) {
            if (VA_postal.wtr_nav[id] == null) {
                VA_postal.wtr_nav[id] = VA_postal.wtr_npc[id].getNavigator();
                cit_new_nav = C_Citizens.alt_local_nav();
                cit_distanceMargin = C_Citizens.distanceMargin();
                cit_avoidWater = C_Citizens.avoidWater();
                cit_stationaryTicks = C_Citizens.stationaryTicks();
                cit_range = C_Citizens.range();
                cit_ground_waypoint = C_Citizens.ground_waypoint();
                VA_postal.wtr_nav[id].getDefaultParameters().baseSpeed(1.5F);
                VA_postal.wtr_nav[id].getDefaultParameters().avoidWater(cit_avoidWater);
                VA_postal.wtr_nav[id].getDefaultParameters().range(100.0F);
                VA_postal.wtr_nav[id].getDefaultParameters().speedModifier(1.0F);
                if (!cit_new_nav) {
                    VA_postal.wtr_nav[id].getDefaultParameters().useNewPathfinder(false);
                    VA_postal.wtr_nav[id].getDefaultParameters().stationaryTicks(cit_stationaryTicks);
                    VA_postal.wtr_nav[id].getDefaultParameters().stuckAction(VA_postal.wtr_stuck_npc);
                } else {
                    VA_postal.wtr_nav[id].getDefaultParameters().useNewPathfinder(true);
                    VA_postal.wtr_nav[id].getDefaultParameters().stationaryTicks(cit_stationaryTicks);
                    VA_postal.wtr_nav[id].getDefaultParameters().distanceMargin(cit_distanceMargin);
                }
            }

            dynamic_waypoint_adjustment(id);
            dynamic_range_and_speed(id);
        }
    }

    private static synchronized void dynamic_range_and_speed(int id) {
        float speed_scale = VA_postal.wtr_speed;
        float speed_factor = 0.0F;
        int distance = (int)VA_postal.wtr_dist_next[id];
        switch (distance) {
            case 0:
                speed_factor = 0.5F;
                break;
            case 1:
                speed_factor = 0.5F;
                break;
            case 2:
                speed_factor = 0.55F;
                break;
            case 3:
                speed_factor = 0.6F;
                break;
            case 4:
                speed_factor = 0.65F;
                break;
            case 5:
                speed_factor = 0.7F;
                break;
            case 6:
                speed_factor = 0.75F;
                break;
            case 7:
                speed_factor = 0.8F;
                break;
            case 8:
                speed_factor = 0.85F;
                break;
            case 9:
                speed_factor = 0.9F;
                break;
            case 10:
                speed_factor = 0.95F;
                break;
            case 11:
                speed_factor = 1.0F;
                break;
            case 12:
                speed_factor = 1.05F;
                break;
            default:
                speed_factor = 1.109F;
        }

        double watchdog_calc = distance * (1.0F / speed_factor) * 1500.0F;
        if (watchdog_calc <= 1000.0) {
            watchdog_calc = 1000.0;
        }

        if (watchdog_calc >= 15000.0) {
            watchdog_calc = 15000.0;
        }

        float av_speed_factor = (VA_postal.wtr_speed_factor[id] + speed_factor) / 2.0F * speed_scale;
        VA_postal.wtr_watchdog_stuck_ms[id] = (int)watchdog_calc * 2;
        VA_postal.wtr_speed_factor[id] = av_speed_factor;
        Util.dinform("\u001b[0;32mCalculated Dist: " + distance + " Speed: " + av_speed_factor + " Watchdog: " + VA_postal.wtr_watchdog_stuck_ms[id]);
        if (cit_range < 0.0F) {
            double dist_to_target = VA_postal.wtr_npc[id].getBukkitEntity().getLocation().distance(VA_postal.wtr_waypoint[id]);
            VA_postal.wtr_range[id] = (float)(dist_to_target * 1.5);
        }
    }

    private static synchronized void dynamic_waypoint_adjustment(int id) {
        VA_postal.wtr_swaypoint[id] = Util.put_point_on_ground(VA_postal.wtr_swaypoint[id], cit_ground_waypoint);
        VA_postal.wtr_waypoint[id] = Util.str2location(VA_postal.wtr_swaypoint[id]);
    }

    public static synchronized String proper(String string) {
        try {
            if (string.length() > 0) {
                return string.substring(0, 1).toUpperCase() + string.substring(1).toLowerCase().trim();
            }
        } catch (Exception e) {
        }

        return "";
    }

    public static synchronized String df(String string) {
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

    public static synchronized String fixed_len(String input, int len, String filler) {
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
