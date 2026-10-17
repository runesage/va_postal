package com.vodhanel.minecraft.va_postal.economy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyMathTest {
    @Test
    void upkeepSumsTheConfiguredTerms() {
        // base 50 + 5 x 3 addresses + 1 x 20 waypoints + 10% of 200 revenue
        assertEquals(105.0, DailyMath.upkeep(50, 5, 3, 1, 20, 0.1, 200));
        assertEquals(50.0, DailyMath.upkeep(50, 0, 3, 0, 20, 0, 200));
    }

    @Test
    void upkeepIsPaidOnlyFromAboveTheReserve() {
        assertEquals(55.0, DailyMath.payable(55, 1000, 750)); // plenty above the reserve
        assertEquals(20.0, DailyMath.payable(55, 770, 750));  // only 20 above: the rest stays in arrears
        assertEquals(0.0, DailyMath.payable(55, 700, 750));   // below the reserve: the seed is never touched
    }

    @Test
    void poolIsReleaseRateOfSurplusPlusCarryButNeverMoreThanSurplus() {
        assertEquals(500.0, DailyMath.pool(11000, 10000, 0.5, 0));
        assertEquals(600.0, DailyMath.pool(11000, 10000, 0.5, 100));
        assertEquals(1000.0, DailyMath.pool(11000, 10000, 0.5, 5000)); // capped at the surplus
        assertEquals(0.0, DailyMath.pool(9000, 10000, 0.5, 300));      // no surplus, no dividend
    }

    @Test
    void dividendGoesByWorkAndIsCappedByRevenue() {
        Map<String, Double> work = new LinkedHashMap<>();
        work.put("busy", 300.0);
        work.put("quiet", 100.0);
        work.put("idle", 0.0);
        Map<String, Double> revenue = new LinkedHashMap<>(work);
        Map<String, Double> paid = DailyMath.allocate(100, work, revenue, 0.4);
        assertEquals(75.0, paid.get("busy"));   // 3/4 of the pool, under its cap of 120
        assertEquals(25.0, paid.get("quiet"));  // 1/4, under its cap of 40
        assertTrue(!paid.containsKey("idle"));  // no work, no dividend
    }

    @Test
    void capLimitsALargePoolAndTheRestCarries() {
        Map<String, Double> work = Map.of("a", 100.0);
        Map<String, Double> paid = DailyMath.allocate(1000, work, work, 0.4);
        assertEquals(40.0, paid.get("a"));
    }

    @Test
    void capStaysBelowHalfSoSelfMailingStaysALoss() {
        Map<String, Double> work = Map.of("a", 100.0);
        assertEquals(45.0, DailyMath.allocate(1000, work, work, 0.9).get("a"));
        // Owner of two offices mails across them: pays x, gets back 2x/3 in shares + at most 0.5 x 2x/3.
        double x = 6, back = 2 * x / 3 + DailyMath.MAX_CAP * 2 * x / 3;
        assertTrue(back < x);
    }

    @Test
    void deliveriesBasisSplitsByCountButStillCapsByRevenue() {
        Map<String, Double> deliveries = Map.of("a", 1.0, "b", 1.0);
        Map<String, Double> revenue = Map.of("a", 10.0, "b", 1000.0);
        Map<String, Double> paid = DailyMath.allocate(200, deliveries, revenue, 0.4);
        assertEquals(4.0, paid.get("a"));    // half the pool would be 100, capped at 0.4 x 10
        assertEquals(100.0, paid.get("b"));
    }
}
