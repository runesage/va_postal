package com.vodhanel.minecraft.va_postal.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link SqlMailStore} contract, run against every backend (docs/design/persistent-state.md §11): SQLite
 * ({@link SqliteMailStoreTest}) and MySQL/MariaDB ({@link MariaDbMailStoreTest}), so both dialects behave the same.
 */
abstract class MailStoreContract {
    protected DataSource ds;
    protected SqlMailStore store;

    /** A data source on an empty database (no Postal tables). */
    protected abstract DataSource fresh_database() throws Exception;

    protected abstract Dialect dialect();

    @BeforeEach
    void open() throws Exception {
        ds = fresh_database();
        store = new SqlMailStore(ds, "main", null, dialect());
    }

    @AfterEach
    void close() {
        store.close();
    }

    private MailRecord letter() {
        return store.create(MailRecord.new_letter(UUID.randomUUID(), "main", "Testville", "Riverside", "Mill",
                UUID.randomUUID(), null, "{}".getBytes(StandardCharsets.UTF_8), 4440, 1L), Actor.system("test"), "posted");
    }

    @Test
    void createsAndReadsBack() {
        MailRecord r = letter();
        assertEquals(MailState.POSTED, r.state);
        assertEquals(Custody.NONE, r.custody);
        assertEquals("riverside", r.dest_office);
        assertEquals(0, r.version);
        assertEquals(1, store.history(r.id).size());
    }

    @Test
    void moveIsTwoPhase() {
        MailRecord r = letter();
        Custody branch = Custody.chest("world,20,-60,0");
        MailRecord begun = store.begin_move(r, MailState.AT_ORIGIN_BRANCH, branch, Actor.postman("testville"), null);
        assertTrue(begun.moving());
        assertEquals(MailState.POSTED, begun.state);          // still where it was until committed
        assertEquals(Custody.NONE, begun.custody);
        assertEquals(branch, begun.pending_custody);
        MailRecord done = store.commit_move(begun, Actor.postman("testville"), null);
        assertFalse(done.moving());
        assertEquals(MailState.AT_ORIGIN_BRANCH, done.state);
        assertEquals(branch, done.custody);
        assertEquals(3, store.history(r.id).size());
    }

    @Test
    void staleVersionLosesTheRace() {
        MailRecord r = letter();
        store.begin_move(r, MailState.AT_ORIGIN_BRANCH, Custody.chest("a"), Actor.system("one"), null);
        assertThrows(ConflictException.class,
                () -> store.begin_move(r, MailState.AT_ORIGIN_BRANCH, Custody.chest("b"), Actor.system("two"), null));
    }

    @Test
    void cancelLeavesTheMailWhereItWas() {
        MailRecord r = letter();
        MailRecord begun = store.begin_move(r, MailState.AT_ORIGIN_BRANCH, Custody.chest("a"), Actor.system("t"), null);
        MailRecord back = store.cancel_move(begun, Actor.system("t"), "chest full");
        assertFalse(back.moving());
        assertEquals(MailState.POSTED, back.state);
        assertEquals(Custody.NONE, back.custody);
    }

    @Test
    void oneMoveAtATime() {
        MailRecord begun = store.begin_move(letter(), MailState.AT_ORIGIN_BRANCH, Custody.chest("a"), Actor.system("t"), null);
        assertThrows(ConflictException.class,
                () -> store.begin_move(begun, MailState.AT_CENTRAL, Custody.chest("c"), Actor.system("t"), null));
        assertThrows(ConflictException.class,
                () -> store.transition(begun, MailState.MISSING, Custody.NONE, Actor.system("t"), null));
    }

    @Test
    void deliveredMailNeverMovesAgain() {
        MailRecord d = store.transition(letter(), MailState.DELIVERED, Custody.chest("home"), Actor.system("t"), null);
        assertThrows(ConflictException.class,
                () -> store.begin_move(d, MailState.AT_ORIGIN_BRANCH, Custody.chest("a"), Actor.system("t"), null));
        assertTrue(store.held_here().isEmpty());
    }

    @Test
    void heldHereListsChestsAndMovesInFlight() {
        MailRecord a = store.transition(letter(), MailState.AT_ORIGIN_BRANCH, Custody.chest("po"), Actor.system("t"), null);
        MailRecord b = store.begin_move(letter(), MailState.AT_ORIGIN_BRANCH, Custody.chest("po"), Actor.system("t"), null);
        letter(); // still with its sender: not held
        List<MailRecord> held = store.held_here();
        assertEquals(2, held.size());
        assertTrue(held.stream().anyMatch(r -> r.id.equals(a.id)));
        assertTrue(held.stream().anyMatch(r -> r.id.equals(b.id)));
    }

    // ---- Items never cross servers: each layer on its own --------------------------------

    @Test
    void storeRefusesToSendANonLetterIntoTheNetwork() {
        MailRecord letter = letter();
        MailRecord parcel = store.create(new MailRecord(UUID.randomUUID(), MailKind.PARCEL, MailState.AT_CENTRAL, 0, "main",
                "main", "a", "b", "c", "main", Custody.chest("central"), null, null, null, null, 0, 0, "PARCEL_ITEMS_V1",
                new byte[0], 4440, 1L, 1L), Actor.system("t"), null);
        assertThrows(ConflictException.class,
                () -> store.begin_move(parcel, MailState.IN_NETWORK, Custody.NONE, Actor.central(), null));
        assertThrows(ConflictException.class,
                () -> store.transition(parcel, MailState.IN_NETWORK, Custody.NONE, Actor.central(), null));
        // A letter may.
        assertEquals(MailState.IN_NETWORK,
                store.transition(letter, MailState.IN_NETWORK, Custody.NONE, Actor.central(), null).state);
    }

    @Test
    void databaseConstraintRejectsAParcelInTheNetworkEvenFromRawSql() throws SQLException {
        MailRecord parcel = store.create(new MailRecord(UUID.randomUUID(), MailKind.PARCEL, MailState.AT_CENTRAL, 0, "main",
                "main", "a", "b", "c", "main", Custody.chest("central"), null, null, null, null, 0, 0, "PARCEL_ITEMS_V1",
                new byte[0], 4440, 1L, 1L), Actor.system("t"), null);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.executeUpdate(
                    "UPDATE mail SET state = 'IN_NETWORK' WHERE mail_id = '" + parcel.id + "'"));
        }
    }

    @Test
    void reopeningDoesNotReapplyMigrations() {
        MailRecord r = letter();
        store.close();
        store = new SqlMailStore(ds, "main", null, dialect());
        assertTrue(store.get(r.id).isPresent());
    }

    @Test
    void routeRunsAreRecordedAndCleared() {
        store.start_run("run-1", "Testville", "Home", "npc-7", 1L);
        assertEquals(1, store.runs().size());
        assertEquals("testville", store.runs().get(0)[1]);
        store.end_run("run-1");
        assertTrue(store.runs().isEmpty());
    }

    @Test
    void previousChestIsWhereARolledBackWorldStillHasIt() {
        MailRecord r = letter();
        Custody home = Custody.chest("world,40,-60,0");
        Custody office = Custody.chest("world,20,-60,0");
        r = store.commit_move(store.begin_move(r, MailState.AT_ORIGIN_BRANCH, office, Actor.system("t"), null), Actor.system("t"), null);
        assertNull(store.previous_chest(r)); // it was with its sender before
        r = store.transition(r, MailState.OUT_FOR_DELIVERY, office, Actor.system("t"), null); // same chest
        r = store.commit_move(store.begin_move(r, MailState.DELIVERED, home, Actor.system("t"), null), Actor.system("t"), null);
        assertEquals(office, store.previous_chest(r));
    }

    @Test
    void deliveredSinceListsOnlyRecentDeliveries() {
        long before = System.currentTimeMillis() - 1;
        MailRecord d = store.transition(letter(), MailState.DELIVERED, Custody.chest("home"), Actor.system("t"), null);
        letter();
        assertEquals(List.of(d.id), store.delivered_since(before).stream().map(r -> r.id).toList());
        assertTrue(store.delivered_since(System.currentTimeMillis() + 60_000).isEmpty());
    }

    @Test
    void parcelKeepsItsHoldAndTerms() {
        MailRecord p = store.create(MailRecord.new_parcel(UUID.randomUUID(), "main", "Testville", "Testville", "Home",
                UUID.randomUUID(), null, new byte[]{1, 2, 3}, 4440, 1L, "hold-1"), Actor.system("test"), "packaged");
        assertEquals(MailKind.PARCEL, p.kind);
        assertEquals("PARCEL_V1", p.payload_format);
        assertEquals("hold-1", p.hold_id);
        assertArrayEquals(new byte[]{1, 2, 3}, p.payload);

        MailRecord cod = store.set_terms(p, 350.0D, "hold-2", Actor.player(p.sender), "COD set to 350");
        assertEquals(350.0D, cod.cod_amount);
        assertEquals("hold-2", cod.hold_id);
        assertEquals(MailState.POSTED, cod.state);
        assertEquals(p.version + 1, cod.version);
        assertEquals(2, store.history(p.id).size());
        assertThrows(ConflictException.class, () -> store.set_terms(p, 1.0D, null, Actor.system("test"), "stale"));
    }

    @Test
    void acceptingADeliveredParcelHappensOnce() {
        MailRecord p = store.create(MailRecord.new_parcel(UUID.randomUUID(), "main", "Testville", "Testville", "Home",
                UUID.randomUUID(), null, new byte[0], 4440, 1L, null), Actor.system("test"), "packaged");
        MailRecord delivered = store.transition(p, MailState.DELIVERED, Custody.chest("world,40,-60,0"), Actor.system("test"), null);
        store.transition(delivered, MailState.ACCEPTED, Custody.NONE, Actor.system("test"), "accepted");
        // A second accept from the same (now stale) record loses: items are handed over exactly once.
        assertThrows(ConflictException.class,
                () -> store.transition(delivered, MailState.ACCEPTED, Custody.NONE, Actor.system("test"), "again"));
        assertTrue(MailState.ACCEPTED.terminal());
        assertTrue(store.held_here().stream().noneMatch(r -> r.id.equals(p.id)));
    }

    @Test
    void migrationScriptSplitsAndDropsComments() {
        List<String> parts = SqlMailStore.statements("-- c\nCREATE TABLE a (x INT); -- trailing\nCREATE TABLE b (y INT);\n");
        assertEquals(List.of("CREATE TABLE a (x INT)", "CREATE TABLE b (y INT)"), parts);
    }

    // ---- P3: the network-ready parts ---------------------------------------------------------

    @Test
    void migratesToTheLatestSchema() {
        assertEquals(SqlMailStore.MIGRATIONS.length, store.schema_version());
        assertEquals(dialect(), store.dialect());
    }

    @Test
    void keepsAParcelPayloadLargerThan64Kb() {
        byte[] big = new byte[300_000];
        new java.util.Random(1).nextBytes(big);
        MailRecord r = store.create(MailRecord.new_parcel(UUID.randomUUID(), "main", "Testville", "Riverside", "Mill",
                UUID.randomUUID(), null, big, 4440, 1L, null), Actor.system("test"), "packaged");
        assertArrayEquals(big, store.get(r.id).orElseThrow().payload);
    }

    @Test
    void publishesAndReplacesThisServersDirectory() {
        UUID owner = UUID.randomUUID();
        store.publish_directory(List.of(
                new DirectoryEntry("main", "Testville", null, owner, true, false, "world,20,-60,2"),
                new DirectoryEntry("main", "Testville", "Home", null, true, false, "world,40,-60,2"),
                new DirectoryEntry("main", "Testville", "Bakery", null, false, false, "world,50,-60,2")), 1L);
        List<DirectoryEntry> all = store.directory(null);
        assertEquals(3, all.size());
        DirectoryEntry office = all.stream().filter(DirectoryEntry::is_office).findFirst().orElseThrow();
        assertEquals("testville", office.office());
        assertEquals(owner, office.owner());
        assertTrue(all.stream().anyMatch(e -> "bakery".equals(e.address()) && !e.open()));
        // Republishing replaces the rows: Bakery is gone.
        store.publish_directory(List.of(
                new DirectoryEntry("main", "Testville", null, owner, true, false, "world,20,-60,2"),
                new DirectoryEntry("main", "Testville", "Home", null, true, false, "world,40,-60,2")), 2L);
        assertEquals(2, store.directory("main").size());
        // Another server's rows are its own.
        SqlMailStore other = new SqlMailStore(ds, "skyblock", null, dialect());
        other.publish_directory(List.of(new DirectoryEntry("skyblock", "Isle", null, null, true, false, null)), 3L);
        assertEquals(3, store.directory(null).size());
        assertEquals(2, store.directory("main").size());
        assertEquals(1, store.directory("skyblock").size());
    }

    @Test
    void noticesTwoServersSharingAnId() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertNull(store.heartbeat(a, 1L, 10L, true));
        assertNull(store.heartbeat(a, 1L, 20L, false));
        // A restart (or a crashed server's leftover row) is simply taken over on the first beat.
        assertNull(store.heartbeat(b, 30L, 30L, true));
        // But now a's next beat finds b's instance: two live servers on one id.
        assertEquals(b, store.heartbeat(a, 1L, 40L, false));
        assertEquals(a, store.heartbeat(b, 30L, 50L, false));
        assertEquals(1, store.servers().size());
        store.sign_off(b);
        assertEquals(0L, store.servers().get(0).last_seen());
    }
}
