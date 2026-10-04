package com.vodhanel.minecraft.va_postal.mail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignManipTest {
    @Test
    void matchesSixteenCharacterNameOnTruncatedSign() {
        // The sign holds the first 15 characters of a 16-character name.
        assertTrue(SignManip.owner_line_matches("Abcdefghijklmno", "Abcdefghijklmnop"));
    }

    @Test
    void ignoresCaseAndColourCodes() {
        assertTrue(SignManip.owner_line_matches("§aRunesage106", "runesage106"));
    }

    @Test
    void rejectsOtherOwners() {
        assertFalse(SignManip.owner_line_matches("§a[Server]", "runesage106"));
        assertFalse(SignManip.owner_line_matches("", "runesage106"));
        assertFalse(SignManip.owner_line_matches(null, "runesage106"));
    }
}
