package com.vodhanel.minecraft.va_postal.mail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodLabelTest {
    @Test
    void roundTrips() {
        assertEquals(12.0, CodLabel.amount(CodLabel.line(12)));
        assertEquals(1234.5, CodLabel.amount(CodLabel.line(1234.5)));
    }

    @Test
    void readsLinesWithCurrencySymbols() {
        assertEquals(12.0, CodLabel.amount("§c  [COD] $$12.00"));
        assertEquals(1234.5, CodLabel.amount("§c  [COD] $1,234.50"));
    }

    @Test
    void nonCodLinesAreZero() {
        assertEquals(0.0, CodLabel.amount("§7  ."));
        assertEquals(0.0, CodLabel.amount(null));
        assertEquals(0.0, CodLabel.amount("§c  [COD] "));
    }
}
