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

    @Test
    void discountComesOutOfTheSendingOfficesShareAndBackToTheSender() {
        Postage.Split s = Postage.settle(15, 0, 15, false, 20); // 20% of 15 = 3, from the origin's third
        assertEquals(5, s.central, EPS);
        assertEquals(2, s.origin, EPS);
        assertEquals(5, s.dest, EPS);
        assertEquals(3, s.refund, EPS);
    }

    @Test
    void discountOnLocalMailLeavesCentralsHalf() {
        Postage.Split s = Postage.settle(15, 0, 10, true, 25); // 2.5 off the office's 5; 5 refunded already
        assertEquals(5, s.central, EPS);
        assertEquals(2.5, s.origin, EPS);
        assertEquals(7.5, s.refund, EPS);
    }

    @Test
    void discountNeverExceedsTheSendingOfficesShare() {
        Postage.Split s = Postage.settle(15, 0, 15, false, 100);
        assertEquals(5, s.central, EPS);
        assertEquals(0, s.origin, EPS);
        assertEquals(5, s.dest, EPS);
        assertEquals(5, s.refund, EPS);
    }

    @Test
    void discountLeavesTheCodSurchargeAlone() {
        Postage.Split s = Postage.settle(15, 10, 15, false, 20);
        assertEquals(10, s.central, EPS);
        assertEquals(7, s.origin, EPS); // 5 - 3 + 5
        assertEquals(3, s.refund, EPS);
    }

    @Test
    void noOrNegativeDiscountChangesNothing() {
        for (double d : new double[]{0, -10, Double.NaN}) {
            Postage.Split s = Postage.settle(15, 0, 15, false, d);
            assertEquals(5, s.origin, EPS);
            assertEquals(0, s.refund, EPS);
        }
    }

    @Test
    void partsAddUpWithADiscount() {
        double[][] cases = {{6, 0, 4}, {15, 10, 10}, {7, 3, 7}, {10, 0, 0}};
        for (double[] c : cases) {
            for (boolean local : new boolean[]{true, false}) {
                for (double d : new double[]{5, 25, 60, 100}) {
                    assertEquals(c[0] + c[1], total(Postage.settle(c[0], c[1], c[2], local, d)), EPS);
                }
            }
        }
    }
}
