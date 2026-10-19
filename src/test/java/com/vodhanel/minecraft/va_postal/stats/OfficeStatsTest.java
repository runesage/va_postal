package com.vodhanel.minecraft.va_postal.stats;

import com.vodhanel.minecraft.va_postal.store.MailFact;
import com.vodhanel.minecraft.va_postal.store.MailState;
import com.vodhanel.minecraft.va_postal.store.OfficeDay;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficeStatsTest {
    private static final long DAY = OfficeStats.DAY_MS;
    private static final long T = 20_000L * DAY; // midnight, day 20000

    private static MailFact fact(String origin, String dest, long picked, long at, MailState from, MailState to) {
        return new MailFact("m", "main", origin, dest, "home", picked, at, from, to);
    }

    private static OfficeDay row(List<OfficeDay> rows, String office) {
        return rows.stream().filter(r -> r.office().equals(office)).findFirst().orElseThrow();
    }

    @Test
    void pickupsDeliveriesAndLossesGoToTheRightOffice() {
        List<MailFact> facts = List.of(
                fact("testville", "riverside", T + 1000, T + 1000, MailState.POSTED, MailState.AT_ORIGIN_BRANCH),
                fact("testville", "riverside", T + 1000, T + 61_000, MailState.OUT_FOR_DELIVERY, MailState.DELIVERED),
                fact("testville", "riverside", T, T + 5000, MailState.AT_ORIGIN_BRANCH, MailState.MISSING),
                fact("testville", "riverside", T, T + 6000, MailState.AT_DEST_BRANCH, MailState.MISSING),
                fact("testville", "riverside", T, T + 7000, MailState.AT_CENTRAL, MailState.MISSING));
        List<OfficeDay> rows = OfficeStats.summarize("main", 20_000, facts, f -> 120_000L);
        assertEquals(2, rows.size()); // Central's loss counts for nobody
        OfficeDay t = row(rows, "testville");
        assertEquals(1, t.sent());
        assertEquals(1, t.lost());
        assertEquals(0, t.delivered());
        OfficeDay r = row(rows, "riverside");
        assertEquals(1, r.delivered());
        assertEquals(1, r.on_time());
        assertEquals(60_000L, r.transit_ms());
        assertEquals(1, r.lost());
    }

    @Test
    void aLateDeliveryIsNotOnTime() {
        List<MailFact> facts = List.of(fact("a", "a", T, T + 300_000, MailState.OUT_FOR_DELIVERY, MailState.DELIVERED));
        OfficeDay a = OfficeStats.summarize("main", 20_000, facts, f -> 120_000L).get(0);
        assertEquals(1, a.delivered());
        assertEquals(0, a.on_time());
    }

    @Test
    void onlyTheDaysOwnFactsCount() {
        List<MailFact> facts = List.of(
                fact("a", "a", T - 10, T - 10, MailState.POSTED, MailState.AT_ORIGIN_BRANCH),
                fact("a", "a", T + DAY, T + DAY, MailState.POSTED, MailState.AT_ORIGIN_BRANCH),
                fact("a", "a", T + 5, T + 5, MailState.POSTED, MailState.AT_ORIGIN_BRANCH));
        List<OfficeDay> rows = OfficeStats.summarize("main", 20_000, facts, f -> 0L);
        assertEquals(1, rows.get(0).sent());
    }

    @Test
    void officeNamesAreCaseInsensitiveAndMissingOnesCountNowhere() {
        List<MailFact> facts = List.of(
                fact("Testville", "x", T, T, MailState.POSTED, MailState.AT_ORIGIN_BRANCH),
                fact("testville", "x", T, T, MailState.POSTED, MailState.AT_ORIGIN_BRANCH),
                fact(null, null, T, T, MailState.POSTED, MailState.AT_ORIGIN_BRANCH));
        List<OfficeDay> rows = OfficeStats.summarize("main", 20_000, facts, f -> 0L);
        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).sent());
    }

    @Test
    void expectedTimeIsTwoRoundsLocallyAndCentralsTwiceBetweenOffices() {
        assertEquals(2 * 60 * 1000L + 30_000L, OfficeStats.expected_ms(true, 60, 100, 1.0, 30));
        assertEquals((60 + 200) * 1500L, OfficeStats.expected_ms(false, 60, 100, 1.5, 0));
        assertEquals(0L, OfficeStats.expected_ms(true, -5, 0, 1.0, -3));
    }

    @Test
    void standingsAddUpDaysAndRank() {
        List<OfficeDay> rows = List.of(
                new OfficeDay("main", "a", 1, 3, 10, 9, 0, 10_000),
                new OfficeDay("main", "a", 2, 2, 10, 8, 1, 10_000),
                new OfficeDay("main", "b", 1, 1, 30, 15, 0, 30_000),
                new OfficeDay("main", "c", 1, 0, 2, 2, 0, 0),
                new OfficeDay("main", "d", 1, 4, 0, 0, 2, 0));
        List<OfficeStats.Standing> all = OfficeStats.standings(rows);
        assertEquals("b", all.get(0).office());
        OfficeStats.Standing a = all.stream().filter(s -> s.office().equals("a")).findFirst().orElseThrow();
        assertEquals(20, a.delivered());
        assertEquals(0.85, a.on_time_rate(), 1e-9);
        assertEquals(1000L, a.average_ms());
        assertEquals(List.of("b", "a", "c"), OfficeStats.busiest(all, 5).stream().map(OfficeStats.Standing::office).toList());
        // c is perfect but below the minimum; a beats b on time
        assertEquals(List.of("a", "b"), OfficeStats.most_punctual(all, 5, 5).stream().map(OfficeStats.Standing::office).toList());
        assertEquals(List.of("b", "a"), OfficeStats.fewest_losses(all, 5, 5).stream().map(OfficeStats.Standing::office).toList());
        assertTrue(OfficeStats.busiest(all, 1).size() == 1);
    }

    @Test
    void dayIsTheUtcDay() {
        assertEquals(20_000, OfficeStats.day(T));
        assertEquals(19_999, OfficeStats.day(T - 1));
        assertEquals(-1, OfficeStats.day(-1));
    }
}
