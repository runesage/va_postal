package com.vodhanel.minecraft.va_postal.inspect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectionTest {
    private static final Inspection.Rules RULES = new Inspection.Rules(5, 0.5, 10);

    private static List<String> what(Inspection.Facts f) {
        return Inspection.findings(f, RULES, "testville", v -> "$" + v).stream().map(Inspection.Finding::what).toList();
    }

    @Test
    void aHealthyOfficeHasNothingToReport() {
        assertTrue(what(new Inspection.Facts(0.0, false, false, 9, 20, 0.9, 0)).isEmpty());
    }

    @Test
    void eachProblemIsFoundWithItsFix() {
        List<Inspection.Finding> all = Inspection.findings(new Inspection.Facts(12.5, false, true, 10, 20, 0.4, 2), RULES, "testville", v -> "$" + v);
        assertEquals(List.of("The office chest is full.", "The office owes $12.5 of upkeep.", "The postman got stuck 10 times.",
                "Only 40% of mail arrived on time.", "2 letters were lost."), all.stream().map(Inspection.Finding::what).toList());
        assertTrue(all.get(1).fix().contains("/postal office testville deposit"));
        all.forEach(f -> assertFalse(f.fix().isEmpty()));
    }

    @Test
    void aMissingChestIsReportedInsteadOfAFullOne() {
        assertEquals(List.of("The office chest can't be found."), what(new Inspection.Facts(0.0, true, true, 0, 0, 0.0, 0)));
    }

    @Test
    void punctualityNeedsEnoughDeliveriesToJudge() {
        assertTrue(what(new Inspection.Facts(0.0, false, false, 0, 4, 0.0, 0)).isEmpty());
        assertEquals(List.of("Only 0% of mail arrived on time."), what(new Inspection.Facts(0.0, false, false, 0, 5, 0.0, 0)));
    }

    @Test
    void oneLostLetterIsSingular() {
        assertEquals(List.of("1 letter was lost."), what(new Inspection.Facts(0.0, false, false, 0, 0, 0.0, 1)));
    }

    @Test
    void aFindingSurvivesBeingStored() {
        Inspection.Finding f = new Inspection.Finding("a|b", "c");
        assertEquals(new Inspection.Finding("a/b", "c"), Inspection.Finding.of_stored(f.stored()));
    }

    @Test
    void anInspectionIsDueWhenNeverDoneOrAfterTheInterval() {
        long day = 86_400_000L;
        assertTrue(Inspection.due(0L, 5L, 7));
        assertFalse(Inspection.due(10 * day, 16 * day, 7));
        assertTrue(Inspection.due(10 * day, 17 * day, 7));
    }
}
