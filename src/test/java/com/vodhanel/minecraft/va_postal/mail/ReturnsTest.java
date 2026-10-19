package com.vodhanel.minecraft.va_postal.mail;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReturnsTest {
    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aStampedLetterKeepsItsContentAndCarriesTheReason() {
        byte[] stamped = Returns.stamped(json("{\"title\":\"b:riverside\",\"author\":\"mill\",\"pages\":[\"hi\"]}"), "no such office");
        String s = new String(stamped, StandardCharsets.UTF_8);
        assertTrue(s.contains("\"title\":\"b:riverside\"") && s.contains("\"pages\":[\"hi\"]"), s);
        assertEquals("no such office", Returns.reason(stamped));
    }

    @Test
    void anOrdinaryLetterHasNoReason() {
        assertNull(Returns.reason(json("{\"title\":\"t\",\"author\":\"a\",\"pages\":[]}")));
        assertNull(Returns.reason(json("not json")));
    }

    @Test
    void aBrokenPayloadStillGetsStamped() {
        assertEquals("why", Returns.reason(Returns.stamped(json("not json"), "why")));
    }

    @Test
    void theReturnedPageSaysWhy() {
        assertTrue(Returns.page("there's no post office called Riverside on b").endsWith("there's no post office called Riverside on b"));
        assertTrue(Returns.page("x").contains("Returned to sender"));
    }
}
