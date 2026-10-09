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

    // ---- P4: letters between servers --------------------------------------------------------

    /** A letter posted on "main" for an office on "skyblock", waiting in main's Central chest. */
    private MailRecord letter_for_skyblock() {
        MailRecord r = store.create(MailRecord.new_letter(UUID.randomUUID(), "main", "skyblock", "Testville", "Isle", "Hut",
                UUID.randomUUID(), UUID.randomUUID(), "{}".getBytes(StandardCharsets.UTF_8), 4440, 1L, null), Actor.system("test"), "posted");
        return store.transition(r, MailState.AT_CENTRAL, Custody.chest("world,0,64,0"), Actor.central(), null);
    }

    @Test
    void oneRowCrossesFromOriginToDestination() {
        SqlMailStore skyblock = new SqlMailStore(ds, "skyblock", null, dialect());
        MailRecord r = letter_for_skyblock();
        assertTrue(r.networked());
        assertEquals(List.of(r.id), store.outbound("world,0,64,0").stream().map(m -> m.id).toList());
        assertTrue(skyblock.in_network().isEmpty());                 // still at main's Central

        MailRecord sent = store.commit_move(store.begin_move(r, MailState.IN_NETWORK, Custody.NONE, Actor.central(), null),
                Actor.central(), null);
        assertEquals(MailState.IN_NETWORK, sent.state);
        assertTrue(store.outbound("world,0,64,0").isEmpty());
        assertTrue(store.held_here().isEmpty());                      // main no longer holds it
        assertTrue(store.in_network().isEmpty());                     // and it isn't main's to claim

        List<MailRecord> waiting = skyblock.in_network();
        assertEquals(1, waiting.size());
        MailRecord claimed = skyblock.claim(waiting.get(0), Custody.chest("sky,5,70,5"), Actor.central());
        assertEquals("skyblock", claimed.custody_server);
        assertEquals(1, skyblock.held_here().size());                 // a move in flight: reconciliation sees it
        MailRecord arrived = skyblock.commit_move(claimed, Actor.central(), null);
        assertEquals(MailState.AT_CENTRAL, arrived.state);
        assertEquals(Custody.chest("sky,5,70,5"), arrived.custody);
        // The same row, with the sender and the whole history on it: no copy on either side.
        assertEquals(r.sender, arrived.sender);
        assertEquals(r.attention, arrived.attention);
        assertEquals(r.id, arrived.id);
        assertTrue(skyblock.in_network().isEmpty());
        List<MailEvent> history = store.history(r.id);
        assertTrue(history.stream().anyMatch(e -> "skyblock".equals(e.server_id)));
        assertTrue(history.stream().anyMatch(e -> "main".equals(e.server_id)));
    }

    @Test
    void ofTwoClaimersOnlyOneWins() {
        SqlMailStore skyblock = new SqlMailStore(ds, "skyblock", null, dialect());
        SqlMailStore other_instance = new SqlMailStore(ds, "skyblock", null, dialect());
        MailRecord r = letter_for_skyblock();
        store.commit_move(store.begin_move(r, MailState.IN_NETWORK, Custody.NONE, Actor.central(), null), Actor.central(), null);
        MailRecord seen_by_a = skyblock.in_network().get(0);
        MailRecord seen_by_b = other_instance.in_network().get(0);
        skyblock.claim(seen_by_a, Custody.chest("sky,5,70,5"), Actor.central());
        assertThrows(ConflictException.class, () -> other_instance.claim(seen_by_b, Custody.chest("sky,9,70,9"), Actor.central()));
    }

    @Test
    void onlyTheAddressedServerCanClaim() {
        SqlMailStore survival = new SqlMailStore(ds, "survival", null, dialect());
        MailRecord r = letter_for_skyblock();
        MailRecord sent = store.commit_move(store.begin_move(r, MailState.IN_NETWORK, Custody.NONE, Actor.central(), null),
                Actor.central(), null);
        assertTrue(survival.in_network().isEmpty());
        assertThrows(ConflictException.class, () -> survival.claim(sent, Custody.chest("s,0,0,0"), Actor.central()));
        assertThrows(ConflictException.class, () -> store.claim(sent, Custody.chest("s,0,0,0"), Actor.central()));
    }

    @Test
    void aLetterForThisServerNeverEntersTheNetwork() {
        MailRecord local = store.transition(letter(), MailState.AT_CENTRAL, Custody.chest("world,0,64,0"), Actor.central(), null);
        assertFalse(local.networked());
        assertTrue(store.outbound("world,0,64,0").isEmpty());
        assertThrows(ConflictException.class,
                () -> store.begin_move(local, MailState.IN_NETWORK, Custody.NONE, Actor.central(), null));
    }

    @Test
    void aParcelIsNeverOutboundOrClaimable() throws SQLException {
        MailRecord parcel = store.create(new MailRecord(UUID.randomUUID(), MailKind.PARCEL, MailState.AT_CENTRAL, 0, "main",
                "skyblock", "a", "b", "c", "main", Custody.chest("world,0,64,0"), null, null, null, null, 0, 0, "PARCEL_ITEMS_V1",
                new byte[0], 4440, 1L, 1L), Actor.system("t"), null);
        assertThrows(ConflictException.class,
                () -> store.begin_move(parcel, MailState.IN_NETWORK, Custody.NONE, Actor.central(), null));
        SqlMailStore skyblock = new SqlMailStore(ds, "skyblock", null, dialect());
        assertThrows(ConflictException.class, () -> skyblock.claim(parcel, Custody.chest("s,0,0,0"), Actor.central()));
        assertTrue(skyblock.in_network().isEmpty());
    }

    @Test
    void localQueriesIgnoreALetterBoundForAnotherServer() {
        // A letter for skyblock's "Isle" must not look like mail for an office of ours with the same name.
        letter_for_skyblock();
        assertTrue(store.by_destination(MailState.AT_CENTRAL, "isle").isEmpty());
        MailRecord ours = store.create(MailRecord.new_letter(UUID.randomUUID(), "main", "Testville", "Isle", "Hut",
                UUID.randomUUID(), null, "{}".getBytes(StandardCharsets.UTF_8), 4440, 1L), Actor.system("test"), "posted");
        store.transition(ours, MailState.AT_CENTRAL, Custody.chest("world,0,64,0"), Actor.central(), null);
        assertEquals(List.of(ours.id), store.by_destination(MailState.AT_CENTRAL, "isle").stream().map(m -> m.id).toList());
    }

    @Test
    void tracksEachPlayerOnceAcrossServers() {
        SqlMailStore skyblock = new SqlMailStore(ds, "skyblock", null, dialect());
        UUID alex = UUID.randomUUID();
        store.player_seen(alex, "Alex", true, 10L);
        // Switching servers: the join on skyblock can arrive before the quit on main.
        skyblock.player_seen(alex, "Alex", true, 20L);
        store.player_seen(alex, "Alex", false, 21L);
        List<NetworkPlayer> found = store.players_named("alex");
        assertEquals(1, found.size());
        assertEquals("skyblock", found.get(0).server_id());
        assertTrue(found.get(0).online());
        // A name change keeps the same row.
        skyblock.player_seen(alex, "Alexandra", true, 30L);
        assertTrue(store.players_named("Alex").isEmpty());
        assertEquals(alex, store.players_named("ALEXANDRA").get(0).id());
        // Leaving skyblock, then skyblock stopping, both leave them offline (and still findable).
        skyblock.player_seen(alex, "Alexandra", false, 40L);
        assertFalse(store.player(alex).orElseThrow().online());
        UUID sam = UUID.randomUUID();
        skyblock.sync_players(List.of(new NetworkPlayer(sam, "Sam", "skyblock", true, 0L)), 50L);
        assertTrue(store.player(sam).orElseThrow().online());
        skyblock.sign_off(UUID.randomUUID());
        assertFalse(store.player(sam).orElseThrow().online());
        // Two players who once had the same name: the most recently seen comes first.
        UUID other_sam = UUID.randomUUID();
        store.player_seen(other_sam, "Sam", true, 60L);
        assertEquals(other_sam, store.players_named("sam").get(0).id());
    }

    @Test
    void aDepartingLetterCarriesItsArrivalTime() {
        SqlMailStore skyblock = new SqlMailStore(ds, "skyblock", null, dialect());
        MailRecord r = letter_for_skyblock();
        MailRecord moving = store.begin_move(r, MailState.IN_NETWORK, Custody.NONE, Actor.central(), null);
        MailRecord sent = store.depart(moving, 123_456_789L, Actor.central(), "left on the mail ship");
        assertEquals(MailState.IN_NETWORK, sent.state);
        assertEquals(Custody.NONE, sent.custody);
        assertEquals(123_456_789L, skyblock.in_network().get(0).due_at);
        assertThrows(ConflictException.class, () -> store.depart(sent, 1L, Actor.central(), null));
    }
}
