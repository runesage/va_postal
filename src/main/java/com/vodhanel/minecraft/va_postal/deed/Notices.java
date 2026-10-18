package com.vodhanel.minecraft.va_postal.deed;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Messages for players who were offline when something about their deeds happened (an application approved or
 * denied, one waiting for them as an office's owner), told when they next join. Kept in notices.yml.
 */
public final class Notices {
    private static final int MAX_PER_PLAYER = 20;

    private Notices() {
    }

    private static File file() {
        return new File(VA_postal.plugin.getDataFolder(), "notices.yml");
    }

    /** Keeps {@code line} for {@code player}'s next join. */
    public static synchronized void add(UUID player, String line) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file());
        List<String> lines = new ArrayList<>(y.getStringList(player.toString()));
        lines.add(line);
        while (lines.size() > MAX_PER_PLAYER) {
            lines.remove(0);
        }
        y.set(player.toString(), lines);
        save(y);
    }

    /** Tells {@code player} what was kept for them, and forgets it. */
    public static synchronized void deliver(Player player) {
        File f = file();
        if (!f.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        List<String> lines = y.getStringList(player.getUniqueId().toString());
        if (lines.isEmpty()) {
            return;
        }
        for (String line : lines) {
            Util.pinform(player, "&7&o" + line);
        }
        y.set(player.getUniqueId().toString(), null);
        save(y);
    }

    private static void save(YamlConfiguration y) {
        try {
            y.save(file());
        } catch (IOException e) {
            Util.cinform("[Postal] Couldn't save notices.yml: " + e.getMessage());
        }
    }
}
