package com.vodhanel.minecraft.va_postal.navigation;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.AnsiColor;
import com.vodhanel.minecraft.va_postal.common.Util;
import net.citizensnpcs.api.ai.tree.Behavior;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.ai.event.NavigationEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;

/**
 * Per-postman route behavior, run every tick by the NPC's Citizens behavior controller.
 * <p>
 * Ported from the removed Citizens Goal API: the old GoalSelector is gone, so the behavior registers
 * itself in {@code wtr_goalselector} while active and {@link #finishAndRemove()} takes the place of
 * {@code GoalSelector.finishAndRemove()}.
 */
public class Goal_WTR implements Behavior {
    private int id;
    private boolean finished = false;

    public Goal_WTR(int p_id) {
        id = p_id;
    }

    public void reset() {
        ID_WTR.safe_re_target(id);
    }

    /** Arms the behavior for a fresh run after it was (re)added to the controller. */
    public void rearm() {
        finished = false;
    }

    /** Ends this run; the controller removes and resets the behavior on its next tick. */
    public void finishAndRemove() {
        finished = true;
        if (VA_postal.wtr_goalselector[id] == this) {
            VA_postal.wtr_goalselector[id] = null;
        }
    }

    @EventHandler
    public void navComplete(NavigationCompleteEvent event) { // TODO ERROR HERE?
        Util.dinform("NAV COMPLETE INVOKED FOR " + id);
        if (VA_postal.wtr_npc[id] != event.getNPC()) {
            return;
        }
        if (!ID_WTR.npc_should_run(id)) {
            ID_WTR.clear_goal(id);
            return;
        }
        if (ID_WTR.watchdog_check(id)) {
            return;
        }
        ID_WTR.invoke_next_waypoint(id);
    }

    @EventHandler
    public void navEvent(NavigationEvent event) {
        Util.dinform("NAV EVENT FOR " + id + ": " + event.getNPC().getName());
    }


    public BehaviorStatus run() {
        if (finished) {
            return BehaviorStatus.RESET_AND_REMOVE;
        }
        tick();
        return finished ? BehaviorStatus.RESET_AND_REMOVE : BehaviorStatus.RUNNING;
    }

    private void tick() {

        //Util.dinform("------");
        //Util.dinform("Called run for " + id);


        VA_postal.wtr_goalselector[id] = this;
/*
        Util.dinform(AnsiColor.MAGENTA + id + " " + VA_postal.wtr_nav[id] + " "
                + AnsiColor.YELLOW + VA_postal.wtr_nav[id].getTargetAsLocation() + " ");
        Util.dinform( AnsiColor.GREEN+ VA_postal.wtr_nav[id].getNPC().isSpawned()+" " + VA_postal.wtr_nav[id].getNPC().getStoredLocation()+" "
                + AnsiColor.BLUE + VA_postal.wtr_nav[id].isNavigating() + " " + VA_postal.wtr_nav[id].isPaused());
        Util.dinform(AnsiColor.YELLOW+VA_postal.wtr_npc_player[id]);
        */

        if (!ID_WTR.npc_should_run(id)) {
            Util.dinform("NPC SHOULD NOT RUN: " + id);
            ID_WTR.clear_goal(id);
            return;
        }

        if (!VA_postal.wtr_npc_player[id].isValid()) {
            Util.dinform(AnsiColor.RED + id + " player is not valid " + AnsiColor.L_WHITE + VA_postal.wtr_npc_player[id].getPlayer());
            VA_postal.wtr_npc_player[id] = (Player) VA_postal.wtr_npc[id].getEntity();
        }

        if (VA_postal.wtr_npc[id] == null)
            Util.dinform(AnsiColor.RED + "NPC IS NULL: " + id);

        if (VA_postal.wtr_npc[id].getEntity() == null) {
            Util.dinform(AnsiColor.RED + "NPC ENTITY IS NULL: " + id);
            Util.dinform(AnsiColor.RED + "isSpawned: "+VA_postal.wtr_npc[id].isSpawned());
            Util.dinform("reinitialising...");
            VA_postal.wtr_npc[id].spawn(Util.str2location(VA_postal.wtr_slocation_local_po_spawn[id]));
        }


        if (!VA_postal.wtr_npc[id].getEntity().isValid()) {
            Util.dinform(AnsiColor.YELLOW + id + " entity is not valid");
        }

        if (ID_WTR.watchdog_check(id)) {
            //Util.dinform("Watchdog called true for " + id);
            return;
        }


        if (VA_postal.wtr_not_postal_fired[id]) {
            VA_postal.wtr_not_postal_fired[id] = false;
            VA_postal.wtr_watchdog_stuck_retry[id] = 0;

            Util.dinform(AnsiColor.L_GREEN + "R: New target for " + id + " " + VA_postal.wtr_waypoint[id]);
            VA_postal.wtr_nav[id].setTarget(VA_postal.wtr_waypoint[id]);
        }


        if (VA_postal.wtr_postal_route_start[id]) {
            VA_postal.wtr_postal_route_start[id] = false;
            ID_WTR.start_postal_route(id);
            //Util.dinform("Started route for " + id);
        }


        if (ID_WTR.at_waypoint(id)) {
            Util.dinform(AnsiColor.L_GREEN + id + " IS AT WAYPOINT");
            ID_WTR.invoke_next_waypoint(id);
            return;
        }

        //Util.dinform(AnsiColor.L_YELLOW + id + " IS NOT AT WAYPOINT");

        ID_WTR.safe_re_target(id);
    }

    public boolean shouldExecute() {
        if (finished) {
            return false;
        }
        VA_postal.wtr_goalselector[id] = this;
        return ID_WTR.npc_should_run(id);
    }
}
