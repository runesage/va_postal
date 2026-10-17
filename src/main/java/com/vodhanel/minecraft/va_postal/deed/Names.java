package com.vodhanel.minecraft.va_postal.deed;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** names.yml (the house-name generator's words and shapes), loaded once from the plugin's folder. */
final class Names {
    private static HouseNames.Words words;

    private Names() {
    }

    static synchronized HouseNames.Words words() {
        if (words == null) {
            YamlConfiguration y = DeedDocument.load("names.yml");
            words = HouseNames.words(plain(y.getConfigurationSection("words")), y.getStringList("shapes"),
                    plain(y.getConfigurationSection("settings")));
        }
        return words;
    }

    static synchronized void reload() {
        words = null;
    }

    /** A section as plain maps and lists, as HouseNames reads them. */
    private static Map<String, Object> plain(ConfigurationSection s) {
        Map<String, Object> out = new HashMap<>();
        if (s == null) {
            return out;
        }
        for (String key : s.getKeys(false)) {
            Object v = s.get(key);
            out.put(key, v instanceof ConfigurationSection c ? plain(c) : v instanceof List<?> l ? l : v);
        }
        return out;
    }
}
