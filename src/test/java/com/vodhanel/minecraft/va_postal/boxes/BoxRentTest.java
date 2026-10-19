package com.vodhanel.minecraft.va_postal.boxes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoxRentTest {
    @Test
    void aRenterWhoCanPayPaysTheDayAndClearsArrears() {
        BoxRent.Day d = BoxRent.day(5, 7.5, 2, 100, 3);
        assertEquals(12.5, d.paid());
        assertEquals(0.0, d.arrears());
        assertEquals(0, d.arrears_days());
        assertFalse(d.lapses());
    }

    @Test
    void whatCantBePaidBecomesArrears() {
        BoxRent.Day d = BoxRent.day(5, 0, 0, 2, 3);
        assertEquals(2.0, d.paid());
        assertEquals(3.0, d.arrears());
        assertEquals(1, d.arrears_days());
        assertFalse(d.lapses());
    }

    @Test
    void theLeaseLapsesAfterTheGracePeriod() {
        BoxRent.Day d = BoxRent.day(5, 15, 3, 0, 3); // owed for 3 days already: the 4th is one too many
        assertEquals(20.0, d.arrears());
        assertEquals(4, d.arrears_days());
        assertTrue(d.lapses());
        assertFalse(BoxRent.day(5, 10, 2, 0, 3).lapses()); // the 3rd day is still within the grace
    }

    @Test
    void freeBoxesNeverOwe() {
        BoxRent.Day d = BoxRent.day(0, 0, 0, 0, 3);
        assertEquals(0.0, d.paid());
        assertEquals(0.0, d.arrears());
        assertFalse(d.lapses());
    }

    @Test
    void aNegativeBalancePaysNothing() {
        assertEquals(0.0, BoxRent.day(5, 0, 0, -50, 3).paid());
    }
}
