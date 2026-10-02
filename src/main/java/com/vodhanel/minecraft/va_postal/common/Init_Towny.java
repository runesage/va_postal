package com.vodhanel.minecraft.va_postal.common;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.vodhanel.minecraft.va_postal.VA_postal;

public class Init_Towny implements Runnable {
    VA_postal plugin;
    Towny towny;

    public Init_Towny(VA_postal plugin) {
        this.plugin = plugin;
        towny = plugin.getTowny();
    }

    public void run() {
        plugin.getLogger().info("================================================");
        int hits = TownyUniverse.getInstance().getResidents().size();
        plugin.getLogger().info("Postal registered " + hits + " Towny residents");
        plugin.getLogger().info("================================================");
    }
}
