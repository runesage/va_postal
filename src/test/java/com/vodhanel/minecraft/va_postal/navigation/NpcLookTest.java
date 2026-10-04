package com.vodhanel.minecraft.va_postal.navigation;

import org.bukkit.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NpcLookTest {
    @Test
    void parsesUniformColours() {
        assertEquals(Color.fromRGB(0x24345C), NpcLook.parse_color("#24345C"));
        assertEquals(Color.fromRGB(0x9CC3E6), NpcLook.parse_color("9cc3e6"));
    }

    @Test
    void rejectsBadColours() {
        assertNull(NpcLook.parse_color("#24345"));
        assertNull(NpcLook.parse_color("navy"));
        assertNull(NpcLook.parse_color("#GGGGGG"));
    }
}
