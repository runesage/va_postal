package com.vodhanel.minecraft.va_postal.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReservesTest {
    @Test
    void officeOwesHalfOfEachPlayerOwnedAddress() {
        assertEquals(750.0, Reserves.office_liability(3, 500));
        assertEquals(0.0, Reserves.office_liability(0, 500));
    }

    @Test
    void centralOwesOfficePricesLessTheSeedAndTheOtherHalfOfAddresses() {
        // 2 player-owned offices at 5000 (each seeded with a 500 floor), 3 player-owned addresses at 500.
        assertEquals(2 * 4500 + 750.0, Reserves.central_liability(2, 5000, 500, 3, 500));
    }

    @Test
    void officePurchaseSplitsIntoSeedAndCentralShareExactly() {
        double price = 5000, floor = 500;
        assertEquals(price, Reserves.office_seed(price, floor) + Reserves.central_liability(1, price, floor, 0, 500));
        assertEquals(100.0, Reserves.office_seed(100, floor)); // a price below the floor seeds it all
    }

    @Test
    void purchasesFundTheirOwnRefundsExactly() {
        // An address purchase deposits p/2 to the office and p/2 to Central: exactly the liability it adds.
        double p = 500;
        assertEquals(p, Reserves.office_liability(1, p) + Reserves.central_liability(0, 5000, 500, 1, p));
    }

    @Test
    void withdrawableNeverGoesNegative() {
        double reserve = Reserves.office_reserve(750, 500);
        assertEquals(1250.0, reserve);
        assertEquals(250.0, Reserves.withdrawable(1500, reserve));
        assertEquals(0.0, Reserves.withdrawable(1000, reserve));
    }

    @Test
    void centralTargetAddsTheBuffer() {
        assertEquals(15750.0, Reserves.central_target(10750, 5000));
    }
}
