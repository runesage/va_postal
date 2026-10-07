package com.vodhanel.minecraft.va_postal.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class P_BankTest {
    @Test
    void parsesAmountsToCents() {
        assertEquals(100.0, P_Bank.parse_amount("100"));
        assertEquals(1234.57, P_Bank.parse_amount("$1,234.567"));
    }

    @Test
    void rejectsNonPositiveOrJunk() {
        assertEquals(0.0, P_Bank.parse_amount("-5"));
        assertEquals(0.0, P_Bank.parse_amount("0"));
        assertEquals(0.0, P_Bank.parse_amount("lots"));
        assertEquals(0.0, P_Bank.parse_amount("NaN"));
        assertEquals(0.0, P_Bank.parse_amount("Infinity"));
    }
}
