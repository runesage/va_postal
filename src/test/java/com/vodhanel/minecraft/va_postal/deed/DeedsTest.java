package com.vodhanel.minecraft.va_postal.deed;

import com.vodhanel.minecraft.va_postal.store.Deed;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeedsTest {
    private static final UUID STEVE = UUID.randomUUID();

    private static Deed deed(Deed.Kind kind, Deed.State state, int issue, long expires) {
        return new Deed(UUID.randomUUID(), kind, state, STEVE, "Steve", "main", "riverside", "rose_cottage", 100, issue,
                1L, expires, 1L);
    }

    private static DeedDocument.Ref ref(Deed d, int issue) {
        return new DeedDocument.Ref(d.id(), issue);
    }

    private static final long LATER = System.currentTimeMillis() + 60_000L;

    @Test
    void anIssuedDeedIsUsableByItsHolderHere() {
        Deed d = deed(Deed.Kind.ADDRESS, Deed.State.ISSUED, 1, LATER);
        assertNull(Deeds.usable(d, ref(d, 1), STEVE, "main"));
    }

    @Test
    void onlyTheHolderCanUseIt() {
        Deed d = deed(Deed.Kind.ADDRESS, Deed.State.ISSUED, 1, LATER);
        assertEquals("This deed belongs to Steve.", Deeds.usable(d, ref(d, 1), UUID.randomUUID(), "main"));
    }

    @Test
    void anOlderCopyStopsWorkingAfterAReissue() {
        Deed d = deed(Deed.Kind.ADDRESS, Deed.State.ISSUED, 2, LATER);
        assertTrue(Deeds.usable(d, ref(d, 1), STEVE, "main").contains("replaced"));
        assertNull(Deeds.usable(d, ref(d, 2), STEVE, "main"));
    }

    @Test
    void closedPendingExpiredAndForeignDeedsCantBeUsed() {
        assertNotNull(Deeds.usable(null, new DeedDocument.Ref(UUID.randomUUID(), 1), STEVE, "main"));
        for (Deed.State s : new Deed.State[]{Deed.State.USED, Deed.State.EXPIRED, Deed.State.DENIED}) {
            Deed d = deed(Deed.Kind.ADDRESS, s, 1, LATER);
            assertTrue(Deeds.usable(d, ref(d, 1), STEVE, "main").contains("can't be used"), s.name());
        }
        Deed pending = deed(Deed.Kind.ADDRESS, Deed.State.PENDING, 1, LATER);
        assertTrue(Deeds.usable(pending, ref(pending, 1), STEVE, "main").contains("approval"));
        Deed late = deed(Deed.Kind.ADDRESS, Deed.State.ISSUED, 1, 1L);
        assertEquals("This deed has expired.", Deeds.usable(late, ref(late, 1), STEVE, "main"));
        Deed charter = deed(Deed.Kind.CHARTER, Deed.State.ISSUED, 1, LATER);
        assertTrue(Deeds.usable(charter, ref(charter, 1), STEVE, "main").contains("isn't an address deed"));
        Deed d = deed(Deed.Kind.ADDRESS, Deed.State.ISSUED, 1, LATER);
        assertTrue(Deeds.usable(d, ref(d, 1), STEVE, "other").contains("on main"));
    }

    @Test
    void anUnansweredApplicationIsRefundedInFullAnUnusedDeedLessTheFee() {
        assertEquals(100.0D, Deeds.lapse_refund(deed(Deed.Kind.ADDRESS, Deed.State.PENDING, 1, 1L), 0.10D), 1e-9);
        assertEquals(90.0D, Deeds.lapse_refund(deed(Deed.Kind.ADDRESS, Deed.State.ISSUED, 1, 1L), 0.10D), 1e-9);
    }

    @Test
    void storedNamesReadAsPeopleWriteThem() {
        assertEquals("Rose Cottage", DeedDocument.display("rose_cottage"));
        assertEquals("The Old Forge", DeedDocument.display("the_old_forge"));
        assertEquals("Ashdene", DeedDocument.display("ashdene"));
    }

    @Test
    void placeholdersAreFilled() {
        assertEquals("Deed of Rose Cottage, Riverside",
                DeedDocument.fill("Deed of {address}, {office}", java.util.Map.of("{address}", "Rose Cottage", "{office}", "Riverside")));
    }
}
