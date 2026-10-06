package com.vodhanel.minecraft.va_postal.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostageTest {
    private static final double EPS = 1e-9;

    private static double total(Postage.Split s) {
        return s.central + s.origin + s.dest + s.refund;
    }

    @Test
    void outOfTownSplitsInThirdsWithNoRefund() {
        Postage.Split s = Postage.settle(15, 0, 15, false);
        assertEquals(5, s.central, EPS);
        assertEquals(5, s.origin, EPS);
        assertEquals(5, s.dest, EPS);
        assertEquals(0, s.refund, EPS);
    }

    @Test
    void localSplitsInHalvesAndRefundsTheDifference() {
        Postage.Split s = Postage.settle(15, 0, 10, true);
        assertEquals(5, s.central, EPS);
        assertEquals(5, s.origin, EPS);
        assertEquals(0, s.dest, EPS);
        assertEquals(5, s.refund, EPS);
    }

    @Test
    void codSurchargeGoesHalfToCentralHalfToTheSendingOffice() {
        Postage.Split s = Postage.settle(15, 10, 15, false);
        assertEquals(10, s.central, EPS);
        assertEquals(10, s.origin, EPS);
        assertEquals(5, s.dest, EPS);
        assertEquals(25, total(s), EPS);
    }

    @Test
    void neverChargesMoreThanWasHeld() {
        // Prices went up after the mail was addressed.
        Postage.Split s = Postage.settle(15, 0, 20, false);
        assertEquals(15, total(s), EPS);
        assertEquals(0, s.refund, EPS);
    }

    @Test
    void codOnlyHoldChargesNoPostage() {
        Postage.Split s = Postage.settle(0, 10, 15, false);
        assertEquals(5, s.central, EPS);
        assertEquals(5, s.origin, EPS);
        assertEquals(0, s.dest, EPS);
        assertEquals(0, s.refund, EPS);
    }

    @Test
    void partsAlwaysAddUpToWhatWasHeld() {
        double[][] cases = {{6, 0, 4}, {15, 10, 10}, {7, 3, 7}, {10, 0, 0}};
        for (double[] c : cases) {
            for (boolean local : new boolean[]{true, false}) {
                assertEquals(c[0] + c[1], total(Postage.settle(c[0], c[1], c[2], local)), EPS);
            }
        }
    }
}
