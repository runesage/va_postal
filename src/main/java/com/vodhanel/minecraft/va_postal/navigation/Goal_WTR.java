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
    /** A postman who gets no closer to his waypoint for this long is stuck, whatever Citizens thinks. */
    static final long STALL_MS = 30_000L;

    private int id;
    private boolean finished = false;
    // Progress towards the current waypoint, for the stall watchdog.
    private org.bukkit.Location progress_target;
    private double progress_best;
    private long progress_stamp;

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

    /**
     * Ends this run; the controller removes and resets the behavior on its next tick. Like the old
     * GoalSelector, {@code wtr_goalselector} keeps pointing here: route code checks it right after
     * finishing a waypoint, before the behavior is re-added for the next one.
     */
    public void finishAndRemove() {
        finished = true;
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
            if (VA_postal.wtr_npc[id].getEntity() instanceof Player) {
                VA_postal.wtr_npc_player[id] = (Player) VA_postal.wtr_npc[id].getEntity();
                VA_postal.wtr_inventory_npc[id] = VA_postal.wtr_npc_player[id].getInventory();
            }
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


        // Stalled: no closer to his waypoint for STALL_MS (pushing at a wall, jittering on a corner, a path
        // Citizens keeps replanning): teleport him there and carry on. Neither Citizens' stuck action nor the
        // external watchdog (which counts any movement as progress) catches this, and one frozen postman
        // stops his whole office's queue.
        if (stalled()) {
            ID_WTR.report_recovery(id, "Route Navigation, Watchdog Teleport Reset");
            ID_WTR.tp_npc(VA_postal.wtr_npc[id], VA_postal.wtr_waypoint[id].clone().add(0.5, 0, 0.5));
            ID_WTR.invoke_next_waypoint(id);
            return;
        }

        // A door or gate just ahead: Postal takes him through it (Citizens won't plan through doors).
        if (!VA_postal.wtr_waypoint_completed[id] && Doorway.tick(id)) {
            return;
        }

        // A ladder: Postal climbs him (Citizens can't), and finishes the waypoint when he's there.
        if (!VA_postal.wtr_waypoint_completed[id] && Climb.tick(id)) {
            return;
        }

        if (ID_WTR.at_waypoint(id)) {
            Util.dinform(AnsiColor.L_GREEN + id + " IS AT WAYPOINT");
            ID_WTR.invoke_next_waypoint(id);
            return;
        }

        //Util.dinform(AnsiColor.L_YELLOW + id + " IS NOT AT WAYPOINT");

        ID_WTR.safe_re_target(id);
    }

    /** True if the postman has got no closer to his current waypoint for {@link #STALL_MS}. */
    private boolean stalled() {
        org.bukkit.Location target = VA_postal.wtr_waypoint[id];
        long now = System.currentTimeMillis();
        if (target == null || VA_postal.wtr_waypoint_completed[id] || VA_postal.wtr_cooling[id]
                || VA_postal.wtr_npc[id].getEntity() == null
                || !target.getWorld().equals(VA_postal.wtr_npc[id].getEntity().getWorld())) {
            progress_target = null;
            return false;
        }
        double d = VA_postal.wtr_npc[id].getEntity().getLocation().distance(target);
        if (!target.equals(progress_target)) {
            progress_target = target.clone();
            progress_best = d;
            progress_stamp = now;
            return false;
        }
        if (d < progress_best - 0.5D) {
            progress_best = d;
            progress_stamp = now;
            return false;
        }
        if (now - progress_stamp > STALL_MS) {
            progress_target = null;
            return true;
        }
        return false;
    }

    public boolean shouldExecute() {
        if (finished) {
            return false;
        }
        VA_postal.wtr_goalselector[id] = this;
        return ID_WTR.npc_should_run(id);
    }
}
