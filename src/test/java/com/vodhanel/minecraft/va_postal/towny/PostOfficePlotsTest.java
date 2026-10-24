package com.vodhanel.minecraft.va_postal.towny;

import com.vodhanel.minecraft.va_postal.towny.PostOfficePlots.Plot;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostOfficePlotsTest {
    private static final UUID MAYOR = UUID.randomUUID(), OWNER = UUID.randomUUID(), OTHER = UUID.randomUUID();

    private static Plot plot(boolean postoffice, UUID owner, UUID mayor) {
        return new Plot("Rivertown", "world:3:-2", postoffice, owner, mayor);
    }

    @Test
    void theOfficeBelongsToThePlotOwnerElseTheMayorElseTheServer() {
        assertEquals(OWNER, PostOfficePlots.office_owner(plot(true, OWNER, MAYOR)));
        assertEquals(MAYOR, PostOfficePlots.office_owner(plot(true, null, MAYOR)));
        assertNull(PostOfficePlots.office_owner(plot(true, null, null)));
    }

    @Test
    void outsideTownsChartersFollowTheUsualRules() {
        assertNull(PostOfficePlots.charter_problem(null, OTHER, false));
    }

    @Test
    void inATownACharterNeedsAPostOfficePlot() {
        String why = PostOfficePlots.charter_problem(plot(false, OWNER, MAYOR), OWNER, false);
        assertNotNull(why);
        assertTrue(why.contains("Rivertown") && why.contains("/plot set postoffice"), why);
    }

    @Test
    void thePlotOwnerMayOpenAnOfficeOnTheirPlotButNobodyElse() {
        assertNull(PostOfficePlots.charter_problem(plot(true, OWNER, MAYOR), OWNER, false));
        assertNotNull(PostOfficePlots.charter_problem(plot(true, OWNER, MAYOR), OTHER, false));
        assertNotNull(PostOfficePlots.charter_problem(plot(true, OWNER, MAYOR), MAYOR, false)); // even the mayor
    }

    @Test
    void onATownOwnedPlotOnlyTheMayorMayFound() {
        assertNull(PostOfficePlots.charter_problem(plot(true, null, MAYOR), MAYOR, false));
        assertNotNull(PostOfficePlots.charter_problem(plot(true, null, MAYOR), OTHER, false));
        assertNotNull(PostOfficePlots.charter_problem(plot(true, null, null), OTHER, false));
        assertNotNull(PostOfficePlots.charter_problem(plot(true, null, null), null, false));
    }

    @Test
    void onePostOfficePerPlot() {
        assertNotNull(PostOfficePlots.charter_problem(plot(true, OWNER, MAYOR), OWNER, true));
    }

    @Test
    void aTownOfficesCabinetStandsOnItsPlot() {
        Plot office = plot(true, OWNER, MAYOR);
        assertNull(PostOfficePlots.cabinet_problem(office, plot(true, OWNER, MAYOR)));
        assertNotNull(PostOfficePlots.cabinet_problem(office, new Plot("Rivertown", "world:4:-2", true, OWNER, MAYOR)));
        assertNotNull(PostOfficePlots.cabinet_problem(office, null)); // outside the town
        // Offices that aren't on post office plots have no rule.
        assertNull(PostOfficePlots.cabinet_problem(null, null));
        assertNull(PostOfficePlots.cabinet_problem(plot(false, OWNER, MAYOR), null));
    }

    @Test
    void anAddressGoesOnAPlotTheHolderOwns() {
        assertNull(PostOfficePlots.address_problem(null, OTHER, false)); // outside towns
        assertNull(PostOfficePlots.address_problem(plot(false, OWNER, MAYOR), OWNER, false));
        String why = PostOfficePlots.address_problem(plot(false, OWNER, MAYOR), OTHER, false);
        assertNotNull(why);
        assertTrue(why.contains("someone else's plot"), why);
        // Not even the mayor on a resident's plot.
        assertNotNull(PostOfficePlots.address_problem(plot(false, OWNER, MAYOR), MAYOR, true));
    }

    @Test
    void onATownOwnedPlotOnlyTheMayorOrAnAssistantMayMakeAnAddress() {
        assertNull(PostOfficePlots.address_problem(plot(false, null, MAYOR), MAYOR, true));
        assertNull(PostOfficePlots.address_problem(plot(false, null, MAYOR), OTHER, true)); // an assistant
        String why = PostOfficePlots.address_problem(plot(false, null, MAYOR), OTHER, false);
        assertNotNull(why);
        assertTrue(why.contains("Rivertown") && why.contains("/plot claim"), why);
    }
}
