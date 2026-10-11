package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.mail.Book;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Text must behave exactly like the deprecated Bukkit calls it replaces. */
@SuppressWarnings("deprecation")
class TextTest {
    private static final String[] SAMPLES = {
            "", "plain", "&aGreen &lbold&r reset", "&Z not a code", "trailing &", "&&a doubled", "&x&1&2&3&4&5&6hex",
            "§aalready §lsection", "mixed &A upper &K magic", "&7[Postal] &fTestville &8|&7 Home",
    };

    @Test
    void colorMatchesChatColor() {
        for (String s : SAMPLES) {
            assertEquals(org.bukkit.ChatColor.translateAlternateColorCodes('&', s), Text.color(s), s);
        }
        assertNull(Text.color(null));
    }

    @Test
    void stripMatchesChatColor() {
        for (String s : SAMPLES) {
            String colored = Text.color(s);
            assertEquals(org.bukkit.ChatColor.stripColor(colored), Text.strip(colored), s);
            assertEquals(org.bukkit.ChatColor.stripColor(s), Text.strip(s), s);
        }
        assertNull(Text.strip(null));
    }

    @Test
    void postalBookPagesRoundTrip() {
        // Page 1 of an addressed letter, which the stamping code reads back by line and column.
        String page = Book.makeFirstMailPageById("Testville", "Home", "Steve", null, null, "Alex", "A letter",
                "10/11/26 12:00", UUID.randomUUID(), UUID.randomUUID());
        assertEquals(page, Text.legacy(Text.component(page)));
    }

    @Test
    void offlineStandInsGiveTheirNameAsAComponentToo() {
        // Owners who are offline are ServerPlayer proxies; code now asks them for displayName() as well.
        org.bukkit.entity.Player p = ServerPlayer.create(UUID.randomUUID(), "PostalOwner");
        assertEquals("PostalOwner", Text.legacy(p.displayName()));
        assertEquals(p.getDisplayName(), Text.legacy(p.displayName()));
        assertEquals("Server", Text.legacy(ServerPlayer.create(UUID.randomUUID()).displayName()));
    }

    @Test
    void postalSignLinesRoundTrip() {
        for (String line : new String[]{"[Postal_Mail]", "§c[Postal_Mail]", "Testville", "[Local]", "§aHome", ""}) {
            assertEquals(line, Text.legacy(Text.component(line)), line);
        }
    }
}
