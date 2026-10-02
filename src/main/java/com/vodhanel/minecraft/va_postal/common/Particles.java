package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Route-editor particle trails, drawn only for the editing player. */
public class Particles {
    private static final double wide = 0.25;

    public static synchronized void displayLine(Location from, Location to, Player For, Particle effect) {
        if (For == null || effect == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        from = from.clone();
        Vector dir = to.toVector().subtract(from.toVector());
        if (dir.lengthSquared() == 0) {
            return;
        }
        Vector step = dir.clone().normalize().multiply(wide);
        Object data = particle_data(effect);
        for (int counter = 0; counter < (dir.length() / wide); counter++) {
            Location point = from.add(step);
            For.spawnParticle(effect, point, 1, 0, 0, 0, 0, data);
        }
    }

    /** Colour-capable particles take the configured route colour; the rest take no data. */
    private static Object particle_data(Particle effect) {
        Color color = VA_postal.showroute_COL != null ? VA_postal.showroute_COL : Color.WHITE;
        if (effect == Particle.DUST) {
            return new Particle.DustOptions(color, 1.0F);
        }
        if (effect == Particle.ENTITY_EFFECT) {
            return color;
        }
        return null;
    }
}
