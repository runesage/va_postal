package com.vodhanel.minecraft.va_postal.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostalDayTest {
    private static final long DAY = 86400L;

    @Test
    void notDueBeforeADayHasPassed() {
        assertEquals(-1L, PostalDay.due(1000, 1000 + DAY - 1, DAY));
    }

    @Test
    void dueAfterOneDayKeepsTheSchedule() {
        assertEquals(1000 + DAY, PostalDay.due(1000, 1000 + DAY + 30, DAY));
    }

    @Test
    void missedDaysCollapseIntoOneOnTheOriginalTimeOfDay() {
        // Down for 3.5 days: one day runs, recorded at the last scheduled time, not "now".
        assertEquals(1000 + 3 * DAY, PostalDay.due(1000, 1000 + 3 * DAY + DAY / 2, DAY));
    }

    @Test
    void nonPositiveLengthNeverRuns() {
        assertEquals(-1L, PostalDay.due(0, 1_000_000, 0));
    }
}
