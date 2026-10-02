package com.vodhanel.minecraft.va_postal.navigation;

import com.vodhanel.minecraft.va_postal.VA_postal;
import net.citizensnpcs.api.ai.Goal;
import net.citizensnpcs.api.ai.GoalSelector;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import org.bukkit.event.EventHandler;

public class Goal_WTR implements Goal {
    private int id;

    public Goal_WTR(int p_id) {
        this.id = p_id;
    }

    public void reset() {
        ID_WTR.safe_re_target(this.id);
    }

    @EventHandler
    public void navComplete(NavigationCompleteEvent event) {
        if (VA_postal.wtr_npc[this.id] == event.getNPC()) {
            if (!ID_WTR.npc_should_run(this.id)) {
                ID_WTR.clear_goal(this.id);
            } else if (!ID_WTR.watchdog_check(this.id)) {
                ID_WTR.invoke_next_waypoint(this.id);
            }
        }
    }

    public void run(GoalSelector selector) {
        VA_postal.wtr_goalselector[this.id] = selector;
        if (!ID_WTR.npc_should_run(this.id)) {
            ID_WTR.clear_goal(this.id);
        } else if (!ID_WTR.watchdog_check(this.id)) {
            if (VA_postal.wtr_not_postal_fired[this.id]) {
                VA_postal.wtr_not_postal_fired[this.id] = false;
                VA_postal.wtr_watchdog_stuck_retry[this.id] = 0;
                VA_postal.wtr_nav[this.id].setTarget(VA_postal.wtr_waypoint[this.id]);
            }

            if (VA_postal.wtr_postal_route_start[this.id]) {
                VA_postal.wtr_postal_route_start[this.id] = false;
                ID_WTR.start_postal_route(this.id);
            }

            if (ID_WTR.at_waypoint(this.id)) {
                ID_WTR.invoke_next_waypoint(this.id);
            } else {
                ID_WTR.safe_re_target(this.id);
            }
        }
    }

    public boolean shouldExecute(GoalSelector selector) {
        VA_postal.wtr_goalselector[this.id] = selector;
        return ID_WTR.npc_should_run(this.id);
    }
}
