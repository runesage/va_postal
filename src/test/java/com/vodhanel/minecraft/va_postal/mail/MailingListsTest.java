package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.store.ListMember;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailingListsTest {
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();

    private static ListMember row(String list, UUID who, String mode) {
        return new ListMember("main", list, who, "x", mode, 0L);
    }

    @Test
    void listNamesAreLowerCase() {
        assertEquals("office:testville", MailingLists.office_list(" Testville "));
        assertEquals("town:river town", MailingLists.town_list("River Town"));
        assertTrue(MailingLists.opt_out("town:rivertown"));
        assertFalse(MailingLists.opt_out("office:testville"));
    }

    @Test
    void anOfficeListReachesOnlyItsSubscribers() {
        String list = MailingLists.office_list("testville");
        List<ListMember> rows = List.of(row(list, A, ListMember.SUBSCRIBED), row(list, B, ListMember.OPTED_OUT));
        assertEquals(List.of(A), MailingLists.members(list, List.of(A, B, C), rows)); // the group doesn't matter
        assertTrue(MailingLists.members(list, List.of(A, B, C), List.of()).isEmpty());
    }

    @Test
    void aTownListReachesEveryResidentUntilTheyOptOut() {
        String list = MailingLists.town_list("rivertown");
        assertEquals(List.of(A, B, C), MailingLists.members(list, List.of(A, B, C), List.of()));
        List<ListMember> rows = List.of(row(list, B, ListMember.OPTED_OUT), row(list, C, ListMember.SUBSCRIBED));
        assertEquals(List.of(A, C), MailingLists.members(list, List.of(A, B, C), rows));
        // A row for someone who has since left the town doesn't bring them back.
        assertEquals(List.of(A), MailingLists.members(list, List.of(A), List.of(row(list, C, ListMember.SUBSCRIBED))));
    }

    @Test
    void repeatsAreDropped() {
        String list = MailingLists.town_list("rivertown");
        assertEquals(List.of(A, B), MailingLists.members(list, List.of(A, B, A), List.of()));
    }

    @Test
    void costIsPerCopy() {
        assertEquals(15.0, MailingLists.cost(3, 5.0), 1e-9);
        assertEquals(0.0, MailingLists.cost(0, 5.0), 1e-9);
        assertEquals(0.0, MailingLists.cost(3, 0.0), 1e-9);
        assertEquals(0.0, MailingLists.cost(3, Double.NaN), 1e-9);
    }
}
