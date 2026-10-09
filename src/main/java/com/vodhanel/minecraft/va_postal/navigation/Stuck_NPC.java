package com.vodhanel.minecraft.va_postal.navigation;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.StuckAction;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;

public class Stuck_NPC implements StuckAction {
    public Stuck_NPC() {
    }

    public boolean run(NPC npc, Navigator navigator) {
        int id = -1;
        for (int i = 0; VA_postal.wtr_npc != null && i < VA_postal.wtr_npc.length; i++) {
            if ((VA_postal.wtr_npc[i] != null) && (VA_postal.wtr_npc[i] == npc)) {
                id = i;
                break;
            }
        }

        if (id >= 0) {
            long interval = System.currentTimeMillis() - VA_postal.wtr_last_stuck_stamp[id];
            if (interval < 750L) {
                // Citizens asks again every tick while the postman is stuck: only act (and log) every 750 ms.
                return true;
            }
            Location at = npc.getEntity() == null ? null : npc.getEntity().getLocation();
            Location wp = VA_postal.wtr_waypoint[id];
            Util.dinform("STUCKACTION FOR " + id + " at " + Util.location2str(at) + " wp#" + VA_postal.wtr_pos[id] + " "
                    + Util.location2str(wp) + (at != null && wp != null && at.getWorld() == wp.getWorld()
                    ? String.format(" d=%.2f", at.distance(wp.clone().add(0.5, 0, 0.5))) : "")
                    + " nav=" + navigator.isNavigating() + " target=" + Util.location2str(navigator.getTargetAsLocation()));


            if (!ID_WTR.npc_should_run(id)) {
                VA_postal.wtr_last_stuck_stamp[id] = System.currentTimeMillis();
                return false;
            }


            if (ID_WTR.at_waypoint(id)) {
                ID_WTR.invoke_next_waypoint(id);
                VA_postal.wtr_last_stuck_stamp[id] = System.currentTimeMillis();
                return false;
            }

            // On a ladder Postal climbs him itself (Climb); Citizens only sees him standing still.
            if (at != null && wp != null && Climb.on_ladder(wp) && Climb.in_column(at, wp)) {
                VA_postal.wtr_last_stuck_stamp[id] = System.currentTimeMillis();
                return false;
            }


            String stuck_action = VA_postal.wtr_poffice[id] + "," + VA_postal.wtr_address[id] + "," + Util.int2str(VA_postal.wtr_pos[id]);
            String door_given_up = "DOOR_TP," + stuck_action;

            // Stuck on the way to a door: open it and soft-reset, up to five times. v4 then cleared its marker and
            // started the five again, forever (each try also reset the watchdog), so a postman who couldn't reach
            // the door at all, say coming down Loft's ladder, jumped in place for good. Now the sixth time he's
            // teleported to the door and goes on through it.
            if ("DOOR5".equals(VA_postal.wtr_last_stuck_action[id])) {
                ID_WTR.report_recovery(id, "Door Navigation, Teleport Reset");
                ID_WTR.tp_npc(npc, VA_postal.wtr_waypoint[id].clone().add(0.5, 0, 0.5));
                VA_postal.wtr_last_stuck_action[id] = door_given_up;
                ID_WTR.open_door(id, true, true);
                VA_postal.wtr_last_stuck_stamp[id] = System.currentTimeMillis();
                return true;
            }

            if ((VA_postal.wtr_door_nav[id]) && (!VA_postal.wtr_door_nav_enter[id]) &&
                    (!door_given_up.equals(VA_postal.wtr_last_stuck_action[id])) &&
                    (!stuck_action.equals(VA_postal.wtr_last_stuck_action[id]))) {
                if ("DOOR3".equals(VA_postal.wtr_last_stuck_action[id])) {
                    ID_WTR.report_recovery(id, "Door Navigation, Soft Reset");
                }
                ID_WTR.open_door(id, true, true);
                soft_reset_stuck_door(id, npc, "DOOR");
                VA_postal.wtr_last_stuck_stamp[id] = System.currentTimeMillis();
                return true;
            }

            if ((VA_postal.wtr_last_stuck_action[id] != null) && (VA_postal.wtr_last_stuck_action[id].contains("DOOR"))) {
                VA_postal.wtr_last_stuck_action[id] = "";
            }

            if ((VA_postal.wtr_last_stuck_action[id] != null) && (VA_postal.wtr_last_stuck_action[id].equals(stuck_action))) {
                ID_WTR.report_recovery(id, "Route Navigation, Teleport Reset");
                ID_WTR.tp_npc(npc, VA_postal.wtr_waypoint[id].clone().add(0.5, 0, 0.5));
                VA_postal.wtr_last_stuck_action[id] = "";
                ID_WTR.invoke_next_waypoint(id);
                VA_postal.wtr_last_stuck_stamp[id] = System.currentTimeMillis();
                return false;
            }


            if (npc.getEntity().getLocation().distance(VA_postal.wtr_waypoint[id]) > 2.0D) {


                soft_reset_npc_in_place(id, npc, stuck_action);
                VA_postal.wtr_last_stuck_stamp[id] = System.currentTimeMillis();
                return true;
            }


            ID_WTR.tp_npc(npc, VA_postal.wtr_waypoint[id].clone().add(0.5, 0, 0.5));
            VA_postal.wtr_last_stuck_action[id] = "";
            ID_WTR.invoke_next_waypoint(id);
            VA_postal.wtr_last_stuck_stamp[id] = System.currentTimeMillis();
            return false;
        }

        // Not one of Postal's postmen (or it was removed meanwhile): nothing of ours to reset. v4 still
        // wrote wtr_last_stuck_stamp[id] here with id -1 and threw.
        return false;
    }

    public void soft_reset_npc_in_place(int id, NPC npc, String stuck_action) {
        double t_elev = VA_postal.wtr_waypoint[id].getY();
        if (!RouteMngr.cit_ground_waypoint) {
            t_elev -= 1.0D;
        }
        double n_elev = npc.getEntity().getLocation().getY();
        Location target = npc.getEntity().getLocation();
        // Up onto a step at most: with his waypoint a floor or more above him (a ladder, an upper storey), lifting him
        // to its height where he stands would put him into a roof or in mid-air.
        if (t_elev > n_elev && t_elev - n_elev <= 1.0D) {
            target.setY(t_elev);
        } else {
            target.setY(n_elev);
        }


        String starget = Util.put_point_on_ground(Util.location2str(target), RouteMngr.cit_ground_waypoint);
        target = Util.str2location(starget);


        ID_WTR.tp_npc(npc, target.clone().add(0.5, 0, 0.5));


        VA_postal.wtr_nav[id].getDefaultParameters().speedModifier(0.7F);
        ID_WTR.safe_re_target(id);

        VA_postal.wtr_last_stuck_action[id] = stuck_action;
    }

    public void soft_reset_stuck_door(int id, NPC npc, String stuck_action) {
        int pass = -1;
        if ("DOOR".equals(VA_postal.wtr_last_stuck_action[id])) {
            pass = 2;
            stuck_action = "DOOR2";
        } else if ("DOOR2".equals(VA_postal.wtr_last_stuck_action[id])) {
            pass = 3;
            stuck_action = "DOOR3";
        } else if ("DOOR3".equals(VA_postal.wtr_last_stuck_action[id])) {
            pass = 4;
            stuck_action = "DOOR4";
        } else if ("DOOR4".equals(VA_postal.wtr_last_stuck_action[id])) {
            pass = 5;
            stuck_action = "DOOR5";
        } else {
            pass = 1;
        }

        if (pass < 3) {
            VA_postal.wtr_nav[id].getDefaultParameters().speedModifier(0.7F);
            ID_WTR.safe_re_target(id);
            VA_postal.wtr_last_stuck_action[id] = stuck_action;
        } else {
            soft_reset_npc_in_place(id, npc, stuck_action);
        }
    }
}
