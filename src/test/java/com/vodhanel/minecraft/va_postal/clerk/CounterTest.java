package com.vodhanel.minecraft.va_postal.clerk;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CounterTest {
    private static final List<String> BUY = List.of("buy", "suggest");

    @Test
    void aChatAnswerPicksAButtonByItsWord() {
        assertEquals(1, Forms.choose("suggest", BUY));
        assertEquals(1, Forms.choose("  SUGGEST ", BUY));
        assertEquals(-1, Forms.choose("cancel", BUY));
    }

    @Test
    void anythingElseIsTheFieldsValue() {
        assertEquals(0, Forms.choose("Rose Cottage", BUY));
        assertEquals(0, Forms.choose("", BUY));
        assertEquals(0, Forms.choose("buy", BUY)); // the first button's word submits too
        assertEquals("Rose Cottage", Forms.value("Rose Cottage", "Ivy Lodge"));
        assertEquals("Ivy Lodge", Forms.value("", "Ivy Lodge"));
        assertEquals("Ivy Lodge", Forms.value("ok", "Ivy Lodge"));
        assertEquals("Ivy Lodge", Forms.value(null, "Ivy Lodge"));
    }

    @Test
    void pricesAreNonNegativeNumbersRoundedToCents() {
        assertEquals(25.0D, Clerks.parse_price("25"));
        assertEquals(1250.5D, Clerks.parse_price("1,250.50"));
        assertEquals(0.13D, Clerks.parse_price("0.129"));
        assertEquals(0.0D, Clerks.parse_price("0"));
        assertNull(Clerks.parse_price("-5"));
        assertNull(Clerks.parse_price("lots"));
        assertNull(Clerks.parse_price("NaN"));
        assertNull(Clerks.parse_price("Infinity"));
        assertNull(Clerks.parse_price(null));
    }

    @Test
    void theClerkFacesTheWayItIsTold() {
        assertEquals(0.0F, Clerks.yaw(BlockFace.SOUTH));
        assertEquals(90.0F, Clerks.yaw(BlockFace.WEST));
        assertEquals(180.0F, Clerks.yaw(BlockFace.NORTH));
        assertEquals(-90.0F, Clerks.yaw(BlockFace.EAST));
    }

    @Test
    void problemsReadAsSentences() {
        assertEquals("Someone already holds a deed for that name", BuyForm.capital("someone already holds a deed for that name"));
        assertEquals("", BuyForm.capital(null));
    }
}
