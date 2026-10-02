package com.vodhanel.minecraft.va_postal.common;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.commands.Cmdexecutor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class P_WG {
    VA_postal plugin;

    public P_WG(VA_postal instance) {
        plugin = instance;
    }

    public static boolean ok_to_build_wg(Player player) {
        if (VA_postal.wg_configured) {
            if (Cmdexecutor.hasPermission(player, "postal.accept.bypass")) {
                return true;
            }
            Location location = player.getLocation();

            // WorldGuard 7 replaced canBuild() with region queries.
            LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(player);
            if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(local, local.getWorld())) {
                return true;
            }
            return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .testBuild(BukkitAdapter.adapt(location), local);
        }

        return true;
    }
}
