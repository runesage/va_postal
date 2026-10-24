package com.vodhanel.minecraft.va_postal.common;

import com.palmergames.bukkit.towny.event.NewDayEvent;
import com.palmergames.bukkit.towny.event.PlotPreChangeTypeEvent;
import com.palmergames.bukkit.towny.event.TownBlockTypeRegisterEvent;
import com.palmergames.bukkit.towny.event.plot.changeowner.PlotChangeOwnerEvent;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownBlockType;
import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.towny.PostOfficePlots;
import com.vodhanel.minecraft.va_postal.towny.TownyPostOffices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;

/**
 * Towny's plot events, for post office plots (docs/design/towny.md). Registered only when Towny is in use, since it
 * names Towny's classes.
 */
public class P_TownyListener implements Listener {

    /** Towny reloaded its plot types: add ours again. */
    @EventHandler
    public void on_types(TownBlockTypeRegisterEvent event) {
        P_Towny.register_plot_type(VA_postal.plugin.getConfig());
    }

    /** Only the mayor or an assistant makes a post office plot; a plot with an office on it stays one. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_type_change(PlotPreChangeTypeEvent event) {
        if (!VA_postal.using_towny()) {
            return;
        }
        TownBlock tb = event.getTownBlock();
        String key = P_Towny.plot_key(tb);
        if (is_postoffice(event.getOldType()) && !is_postoffice(event.getNewType())) {
            List<String> there = TownyPostOffices.offices_on(key);
            if (!there.isEmpty()) {
                event.setCancelled(true);
                event.setCancelMessage("The " + DeedDocument.display(there.get(0)) + " Post Office stands on this plot:"
                        + " it stays a post office plot while the office is here.");
            }
            return;
        }
        if (is_postoffice(event.getNewType()) && !is_postoffice(event.getOldType())) {
            Resident r = event.getResident();
            String town = tb.getTownOrNull() == null ? null : tb.getTownOrNull().getName();
            if (r == null || !P_Towny.is_mayor_or_assistant(r.getUUID(), town)) {
                event.setCancelled(true);
                event.setCancelMessage("Only the mayor or an assistant can make a post office plot: it's the town's"
                        + " approval of a post office.");
            }
        }
    }

    /** A plot was claimed, bought, given up or taken back: its office follows (a tick later, once Towny is done). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void on_owner_change(PlotChangeOwnerEvent event) {
        if (!VA_postal.using_towny()) {
            return;
        }
        TownBlock tb = event.getTownBlock();
        String key = P_Towny.plot_key(tb);
        VA_postal.plugin.getServer().getScheduler().runTask(VA_postal.plugin, () -> TownyPostOffices.reconcile_plot(key));
    }

    /** After Towny's new day: plots whose owners couldn't pay their tax went back to their towns. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void on_new_day(NewDayEvent event) {
        if (!VA_postal.using_towny()) {
            return;
        }
        VA_postal.plugin.getServer().getScheduler().runTask(VA_postal.plugin, TownyPostOffices::reconcile_all);
    }

    private static boolean is_postoffice(TownBlockType type) {
        return type != null && PostOfficePlots.TYPE.equalsIgnoreCase(type.getName());
    }
}
