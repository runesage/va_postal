package com.vodhanel.minecraft.va_postal.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings read with int_value/double_value/bool_value work whether config.yml quotes them or not. */
class GetConfigValuesTest {
    private static YamlConfiguration config() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(String.join("\n",
                "Storage:", "  Port: '3307'", "  Pool: 8", "  Bad: 'lots'",
                "Network:", "  Transit: '0.5'", "  Departure: 2", "  Broadcast: 'false'", "  Always: true", "  Odd: 'yes'"));
        return c;
    }

    @Test
    void quotedAndPlainNumbersAreRead() throws Exception {
        YamlConfiguration c = config();
        // What Bukkit itself does with the quoted one: ignores it.
        assertEquals(3306, c.getInt("Storage.Port", 3306));
        assertEquals(3307, GetConfig.int_value(c, "Storage.Port", 3306));
        assertEquals(8, GetConfig.int_value(c, "Storage.Pool", 6));
        assertEquals(6, GetConfig.int_value(c, "Storage.Bad", 6));
        assertEquals(6, GetConfig.int_value(c, "Storage.Missing", 6));
        assertEquals(0.5, GetConfig.double_value(c, "Network.Transit", 5.0));
        assertEquals(2.0, GetConfig.double_value(c, "Network.Departure", 10.0));
    }

    @Test
    void quotedAndPlainBooleansAreRead() throws Exception {
        YamlConfiguration c = config();
        assertFalse(GetConfig.bool_value(c, "Network.Broadcast", true));
        assertTrue(GetConfig.bool_value(c, "Network.Always", false));
        assertTrue(GetConfig.bool_value(c, "Network.Odd", true));
        assertFalse(GetConfig.bool_value(c, "Network.Missing", false));
    }
}
