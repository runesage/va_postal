package com.vodhanel.minecraft.va_postal.navigation;

import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;

import java.util.HashMap;
import java.util.Map;

/**
 * Doors, gates and hatches postmen have opened, counted per block: a postman never closes one that another postman is
 * still going through, and a door a player left open stays open (Postal only ever closes what it opened). Shared by
 * {@link Doorway} and {@link Climb}.
 */
final class Openings {
    private static final Map<Block, Integer> held = new HashMap<>();

    private Openings() {
    }

    /**
     * Opens {@code b} for a postman, or joins another postman holding it open. Returns true if he now holds it (and must
     * {@link #release} it); false if it was already open by someone else's hand, or can't be opened.
     */
    static boolean hold(Block b, boolean quiet) {
        Integer n = held.get(b);
        if (n != null) {
            held.put(b, n + 1);
            ID_WTR.set_door_open(b, true, quiet); // in case a player shut it meanwhile
            return true;
        }
        if (b.getBlockData() instanceof Openable o && !o.isOpen()) {
            ID_WTR.set_door_open(b, true, quiet);
            held.put(b, 1);
            return true;
        }
        return false;
    }

    /** A postman is through (or gone): closes {@code b} once no other postman holds it. */
    static void release(Block b, boolean quiet) {
        Integer n = held.get(b);
        if (n == null) {
            return;
        }
        if (n <= 1) {
            held.remove(b);
            ID_WTR.set_door_open(b, false, quiet);
        } else {
            held.put(b, n - 1);
        }
    }

    /** Closes everything postmen opened (shutdown or reload), so no door is left standing open. */
    static void release_all() {
        for (Block b : held.keySet()) {
            ID_WTR.set_door_open(b, false, true);
        }
        held.clear();
    }

    static int held_count(Block b) {
        Integer n = held.get(b);
        return n == null ? 0 : n;
    }
}
