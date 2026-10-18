package com.vodhanel.minecraft.va_postal.deed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OfficeSalesTest {
    @Test
    void centralsFeeIsAShareOfThePriceToTheCent() {
        assertEquals(5.0D, OfficeSales.fee(100.0D, 0.05D));
        assertEquals(0.62D, OfficeSales.fee(12.34D, 0.05D)); // 0.617
        assertEquals(0.0D, OfficeSales.fee(0.0D, 0.05D));
        assertEquals(0.0D, OfficeSales.fee(-50.0D, 0.05D));
        assertEquals(0.0D, OfficeSales.fee(100.0D, 0.0D));
    }

    @Test
    void pricesAreNonNegativeAmountsToTheCent() {
        assertEquals(2500.0D, OfficeSales.parse_price("2,500"));
        assertEquals(0.13D, OfficeSales.parse_price("0.129"));
        assertEquals(0.0D, OfficeSales.parse_price("0"));
        assertNull(OfficeSales.parse_price("-1"));
        assertNull(OfficeSales.parse_price("a lot"));
        assertNull(OfficeSales.parse_price("Infinity"));
    }
}
