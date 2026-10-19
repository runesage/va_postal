package com.vodhanel.minecraft.va_postal.stats;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwardsTest {
    private static OfficeStats.Standing s(String office, int delivered, int on_time, int lost) {
        return new OfficeStats.Standing(office, 0, delivered, on_time, lost, 0L);
    }

    @Test
    void scoreAddsWeightedBusynessPunctualityAndKeeping() {
        assertEquals(3.0, Awards.score(s("a", 10, 10, 0), 10, 1, 1, 1), 1e-9);
        assertEquals(0.5 + 0.8 + 5.0 / 6, Awards.score(s("b", 5, 4, 1), 10, 1, 1, 1), 1e-9); // half as busy, 4/5 on time, 1 lost of 6
        assertEquals(0.0, Awards.score(s("c", 0, 0, 0), 0, 1, 1, 1), 1e-9);
        assertEquals(2.0, Awards.score(s("a", 10, 10, 0), 10, 2, 0, 0), 1e-9); // only deliveries count
    }

    @Test
    void theBestScoreWinsAmongOfficesWithEnoughDeliveries() {
        List<OfficeStats.Standing> all = List.of(s("busy", 40, 20, 4), s("steady", 20, 20, 0), s("tiny", 3, 3, 0));
        assertEquals("steady", Awards.winner(all, 5, 1, 1, 1).office()); // 0.5 + 1 + 1 beats 1 + 0.5 + 0.909
        assertEquals("busy", Awards.winner(all, 5, 5, 1, 1).office());   // weight busyness
        assertNull(Awards.winner(all, 100, 1, 1, 1));
    }

    @Test
    void tiesGoToTheBusierOfficeThenByName() {
        assertEquals("alpha", Awards.winner(List.of(s("beta", 10, 10, 0), s("alpha", 10, 10, 0)), 1, 0, 1, 1).office());
        assertEquals("big", Awards.winner(List.of(s("small", 5, 5, 0), s("big", 9, 9, 0)), 1, 0, 1, 1).office());
    }

    @Test
    void anAwardIsDueOnceAPeriodHasPassed() {
        long day = OfficeStats.DAY_MS;
        assertFalse(Awards.due(0L, 100 * day, 30));       // never started: the clock starts first
        assertFalse(Awards.due(10 * day, 39 * day, 30));
        assertTrue(Awards.due(10 * day, 40 * day, 30));
    }
}
