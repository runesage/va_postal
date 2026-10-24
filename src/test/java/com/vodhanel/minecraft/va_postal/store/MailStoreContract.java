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

    /** Writes return the record without reading it back: what they return must be exactly what a read gives. */
    @Test
    void writesReturnExactlyWhatIsStored() throws Exception {
        MailRecord r = letter();
        MailRecord t = store.transition(r, MailState.AT_ORIGIN_BRANCH, Custody.chest("world,1,2,3"), Actor.system("t"), "in");
        assertSame(t);
        MailRecord b = store.begin_move(t, MailState.AT_CENTRAL, Custody.chest("world,0,0,0"), Actor.system("t"), null);
        assertSame(b);
        MailRecord c = store.commit_move(b, Actor.system("t"), null);
        assertSame(c);
        MailRecord b2 = store.begin_move(c, MailState.AT_DEST_BRANCH, Custody.chest("world,9,9,9"), Actor.system("t"), null);
        assertSame(store.cancel_move(b2, Actor.system("t"), "didn't go"));
    }

    private void assertSame(MailRecord returned) throws Exception {
        MailRecord stored = store.get(returned.id).orElseThrow();
        for (java.lang.reflect.Field f : MailRecord.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Object a = f.get(returned), b = f.get(stored);
            if (a instanceof byte[] ba && b instanceof byte[] bb) {
                assertArrayEquals(bb, ba, f.getName());
            } else {
                assertEquals(String.valueOf(b), String.valueOf(a), f.getName());
            }
        }
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
    void mailingListsKeepOneModePerPlayer() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        store.set_list_mode("Office:Testville", a, "Alex", ListMember.SUBSCRIBED, 10L);
        store.set_list_mode("office:testville", b, "Blair", ListMember.SUBSCRIBED, 11L);
        store.set_list_mode("town:Rivertown", a, "Alex", ListMember.OPTED_OUT, 12L);
        assertEquals(List.of(a, b), store.list_members("office:testville").stream().map(ListMember::player).toList());
        assertEquals(List.of("office:testville", "town:rivertown"), store.lists_of(a).stream().map(ListMember::list).toList());
        store.set_list_mode("office:testville", a, "Alex", ListMember.OPTED_OUT, 13L); // replaces, never duplicates
        assertEquals(List.of(ListMember.OPTED_OUT), store.list_members("office:testville").stream()
                .filter(m -> m.player().equals(a)).map(ListMember::mode).toList());
        store.set_list_mode("office:testville", b, "Blair", null, 14L); // off the list
        assertEquals(List.of(a), store.list_members("office:testville").stream().map(ListMember::player).toList());
        assertTrue(new SqlMailStore(ds, "other", null, dialect()).list_members("office:testville").isEmpty()); // this server's
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
    void setAttentionRewritesTheRecordAndIsVersionChecked() {
        MailRecord l = letter();
        UUID who = UUID.randomUUID();
        byte[] text = "new page one".getBytes(StandardCharsets.UTF_8);
        MailRecord changed = store.set_attention(l, who, text, Actor.player(l.sender), "attention changed");
        assertEquals(who, changed.attention);
        assertArrayEquals(text, changed.payload);
        assertEquals(MailState.POSTED, changed.state);
        assertEquals(l.version + 1, changed.version);
        assertEquals(2, store.history(l.id).size());
        assertThrows(ConflictException.class, () -> store.set_attention(l, null, null, Actor.system("test"), "stale"));

        // Back to "[Resident]" (null) with the text kept.
        MailRecord reset = store.set_attention(changed, null, null, Actor.system("test"), "reset");
        assertNull(reset.attention);
        assertArrayEquals(text, reset.payload);
        assertEquals(changed.version + 1, reset.version);
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

    @Test
    void serversStartingTogetherOnAFreshDatabaseBothOpen() throws Exception {
        store.close();
        DataSource fresh = fresh_database();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            List<java.util.concurrent.Future<SqlMailStore>> opening = new java.util.ArrayList<>();
            for (String id : new String[]{"alpha", "beta", "gamma"}) {
                opening.add(pool.submit(() -> new SqlMailStore(fresh, id, null, dialect())));
            }
            for (java.util.concurrent.Future<SqlMailStore> f : opening) {
                assertEquals(SqlMailStore.MIGRATIONS.length, f.get().schema_version());
            }
        } finally {
            pool.shutdownNow();
        }
        store = new SqlMailStore(fresh, "main", null, dialect());
    }

    // ---- Deeds ----------------------------------------------------------------------------

    private Deed deed(Deed.Kind kind, String server, String office, String name, double price, long created, long expires) {
        return new Deed(UUID.randomUUID(), kind, Deed.State.ISSUED, UUID.randomUUID(), "Steve", server, office, name,
                price, 1, created, expires, created);
    }

    @Test
    void deedsRoundTrip() {
        Deed d = store.create_deed(deed(Deed.Kind.ADDRESS, "main", "riverside", "rose_cottage", 250.5, 10L, 20L));
        assertEquals(d, store.deed(d.id()).orElseThrow());
        assertTrue(store.deed(UUID.randomUUID()).isEmpty());
    }

    @Test
    void anOpenAddressDeedReservesItsNameInItsOffice() {
        Deed d = store.create_deed(deed(Deed.Kind.ADDRESS, "main", "riverside", "rose_cottage", 100, 1L, 99L));
        assertTrue(store.deed_name_reserved(Deed.Kind.ADDRESS, "riverside", "Rose_Cottage"));
        assertThrows(ConflictException.class,
                () -> store.create_deed(deed(Deed.Kind.ADDRESS, "main", "riverside", "ROSE_COTTAGE", 100, 2L, 99L)));
        // Another office may have its own Rose Cottage.
        store.create_deed(deed(Deed.Kind.ADDRESS, "main", "hillcrest", "rose_cottage", 100, 3L, 99L));
        // Once used, the name is the address's, not the deed's: free as far as deeds go.
        assertTrue(store.deed_state(d.id(), Deed.State.ISSUED, Deed.State.USED, 5L));
        assertFalse(store.deed_name_reserved(Deed.Kind.ADDRESS, "riverside", "rose_cottage"));
    }

    @Test
    void aCharterReservesItsNameAcrossTheNetwork() {
        store.create_deed(deed(Deed.Kind.CHARTER, "main", "brookfield", "brookfield", 1000, 1L, 99L));
        assertThrows(ConflictException.class,
                () -> store.create_deed(deed(Deed.Kind.CHARTER, "other", "brookfield", "Brookfield", 1000, 2L, 99L)));
        assertFalse(store.deed_name_reserved(Deed.Kind.ADDRESS, "brookfield", "brookfield"));
    }

    @Test
    void deedStateChangesOnlyFromTheExpectedState() {
        Deed d = store.create_deed(deed(Deed.Kind.ADDRESS, "main", "riverside", "ivy_lodge", 100, 1L, 99L));
        assertFalse(store.deed_state(d.id(), Deed.State.PENDING, Deed.State.ISSUED, 2L));
        assertTrue(store.deed_state(d.id(), Deed.State.ISSUED, Deed.State.USED, 3L));
        assertFalse(store.deed_state(d.id(), Deed.State.ISSUED, Deed.State.EXPIRED, 4L)); // used already: no double use
        Deed after = store.deed(d.id()).orElseThrow();
        assertEquals(Deed.State.USED, after.state());
        assertEquals(3L, after.updated_at());
    }

    @Test
    void aReissueBumpsTheIssueOfAnOpenDeedOnly() {
        Deed d = store.create_deed(deed(Deed.Kind.ADDRESS, "main", "riverside", "the_old_forge", 100, 1L, 99L));
        assertEquals(2, store.reissue_deed(d.id(), 5L).orElseThrow().issue());
        store.deed_state(d.id(), Deed.State.ISSUED, Deed.State.USED, 6L);
        assertTrue(store.reissue_deed(d.id(), 7L).isEmpty());
        assertEquals(2, store.deed(d.id()).orElseThrow().issue());
    }

    @Test
    void dueAndHeldCountThisServersOpenDeedsOnly() {
        Deed late = store.create_deed(deed(Deed.Kind.ADDRESS, "main", "riverside", "ashdene", 100, 1L, 50L));
        store.create_deed(deed(Deed.Kind.ADDRESS, "main", "riverside", "oakhurst", 40.25, 1L, 500L));
        Deed used = store.create_deed(deed(Deed.Kind.ADDRESS, "main", "riverside", "elmleigh", 1000, 1L, 10L));
        store.deed_state(used.id(), Deed.State.ISSUED, Deed.State.USED, 2L);
        store.create_deed(deed(Deed.Kind.ADDRESS, "other", "riverside", "hollycombe", 7, 1L, 10L));
        assertEquals(List.of(late.id()), store.deeds_due(100L).stream().map(Deed::id).toList());
        assertEquals(140.25, store.deeds_held(), 1e-9);
    }

    @Test
    void aPlayersDeedsNewestFirst() {
        UUID steve = UUID.randomUUID();
        Deed first = new Deed(UUID.randomUUID(), Deed.Kind.ADDRESS, Deed.State.ISSUED, steve, "Steve", "main", "riverside",
                "wrens_rest", 1, 1, 10L, 99L, 10L);
        Deed second = new Deed(UUID.randomUUID(), Deed.Kind.ADDRESS, Deed.State.ISSUED, steve, "Steve", "main", "hillcrest",
                "larks_nest", 1, 1, 20L, 99L, 20L);
        store.create_deed(first);
        store.create_deed(second);
        assertEquals(List.of(second.id(), first.id()), store.deeds_of(steve).stream().map(Deed::id).toList());
    }

    @Test
    void anApprovedApplicationIsIssuedWithAFreshExpiry() {
        Deed d = deed(Deed.Kind.ADDRESS, "main", "riverside", "foxgloves", 100, 1L, 50L).with_state(Deed.State.PENDING, 1L);
        store.create_deed(d);
        assertTrue(store.deed_name_reserved(Deed.Kind.ADDRESS, "riverside", "foxgloves")); // pending holds the name
        assertTrue(store.issue_deed(d.id(), 5L, 900L));
        Deed after = store.deed(d.id()).orElseThrow();
        assertEquals(Deed.State.ISSUED, after.state());
        assertEquals(900L, after.expires_at());
        assertEquals(5L, after.updated_at());
        assertFalse(store.issue_deed(d.id(), 6L, 1000L)); // only once, and only from pending
        assertEquals(900L, store.deed(d.id()).orElseThrow().expires_at());
    }

    @Test
    void anOfficesApplicationsOldestFirst() {
        Deed second = deed(Deed.Kind.ADDRESS, "main", "riverside", "larks_nest", 1, 20L, 99L).with_state(Deed.State.PENDING, 20L);
        Deed first = deed(Deed.Kind.ADDRESS, "main", "riverside", "wrens_rest", 1, 10L, 99L).with_state(Deed.State.PENDING, 10L);
        store.create_deed(second);
        store.create_deed(first);
        store.create_deed(deed(Deed.Kind.ADDRESS, "main", "riverside", "issued_one", 1, 5L, 99L));
        store.create_deed(deed(Deed.Kind.ADDRESS, "main", "hillcrest", "elsewhere", 1, 5L, 99L).with_state(Deed.State.PENDING, 5L));
        store.create_deed(deed(Deed.Kind.ADDRESS, "other", "riverside", "not_here", 1, 5L, 99L).with_state(Deed.State.PENDING, 5L));
        assertEquals(List.of(first.id(), second.id()), store.deeds_pending("riverside").stream().map(Deed::id).toList());
        store.deed_state(first.id(), Deed.State.PENDING, Deed.State.DENIED, 30L);
        assertEquals(List.of(second.id()), store.deeds_pending("riverside").stream().map(Deed::id).toList());
    }

    @Test
    void pendingCharterApplicationsOldestFirst() {
        Deed second = deed(Deed.Kind.CHARTER, "main", "brookfield", "brookfield", 1, 20L, 99L).with_state(Deed.State.PENDING, 20L);
        Deed first = deed(Deed.Kind.CHARTER, "main", "ashford", "ashford", 1, 10L, 99L).with_state(Deed.State.PENDING, 10L);
        store.create_deed(second);
        store.create_deed(first);
        store.create_deed(deed(Deed.Kind.CHARTER, "main", "issued", "issued", 1, 5L, 99L));
        store.create_deed(deed(Deed.Kind.ADDRESS, "main", "riverside", "an_address", 1, 5L, 99L).with_state(Deed.State.PENDING, 5L));
        store.create_deed(deed(Deed.Kind.CHARTER, "other", "elsewhere", "elsewhere", 1, 5L, 99L).with_state(Deed.State.PENDING, 5L));
        assert_ids(List.of(first.id(), second.id()), store.deeds_pending(Deed.Kind.CHARTER));
        assertEquals(1, store.deeds_pending(Deed.Kind.ADDRESS).size());
    }

    private static void assert_ids(List<UUID> want, List<Deed> got) {
        assertEquals(want, got.stream().map(Deed::id).toList());
    }

    // ---- Office offers ----------------------------------------------------------------------

    private Offer offer(String server, String office, UUID buyer, double price, long created, long expires) {
        return new Offer(UUID.randomUUID(), server, office, UUID.randomUUID(), "Alex", buyer, "Steve", price, Offer.State.OPEN,
                created, expires, created);
    }

    @Test
    void offersRoundTripAndOneOpenOfferPerOffice() {
        UUID steve = UUID.randomUUID();
        Offer o = store.create_offer(offer("main", "riverside", steve, 1234.5, 10L, 99L));
        assertEquals(o, store.offer(o.id()).orElseThrow());
        assertEquals(o.id(), store.open_offer("riverside").orElseThrow().id());
        assertThrows(ConflictException.class, () -> store.create_offer(offer("main", "riverside", UUID.randomUUID(), 1, 11L, 99L)));
        store.create_offer(offer("other", "riverside", steve, 1, 11L, 99L)); // another server's Riverside
        assertTrue(store.offer_state(o.id(), Offer.State.OPEN, Offer.State.WITHDRAWN, 12L));
        assertFalse(store.offer_state(o.id(), Offer.State.OPEN, Offer.State.ACCEPTED, 13L)); // withdrawn: can't be accepted
        assertTrue(store.open_offer("riverside").isEmpty());
        store.create_offer(offer("main", "riverside", steve, 2, 14L, 99L)); // a new one, once the old is closed
    }

    @Test
    void aBuyersOffersAndTheOnesDue() {
        UUID steve = UUID.randomUUID();
        Offer second = store.create_offer(offer("main", "hillcrest", steve, 1, 20L, 500L));
        Offer first = store.create_offer(offer("main", "riverside", steve, 1, 10L, 50L));
        store.create_offer(offer("main", "brookfield", UUID.randomUUID(), 1, 5L, 40L));
        store.create_offer(offer("other", "ashby", steve, 1, 5L, 40L));
        assertEquals(List.of(first.id(), second.id()), store.offers_to(steve).stream().map(Offer::id).toList());
        assertEquals(2, store.offers_due(100L).size()); // riverside and brookfield, this server's
        store.offer_state(first.id(), Offer.State.OPEN, Offer.State.ACCEPTED, 30L);
        assertEquals(List.of(second.id()), store.offers_to(steve).stream().map(Offer::id).toList());
        assertEquals(1, store.offers_due(100L).size());
    }

    @Test
    void mailFactsListPickupsDeliveriesAndLosses() {
        long before = System.currentTimeMillis() - 1000L;
        MailRecord r = letter();
        MailRecord a = store.transition(r, MailState.AT_ORIGIN_BRANCH, Custody.chest("po"), Actor.system("t"), null);
        MailRecord b = store.begin_move(a, MailState.AT_CENTRAL, Custody.chest("central"), Actor.system("t"), null);
        MailRecord c = store.commit_move(b, Actor.system("t"), null);
        store.transition(c, MailState.DELIVERED, Custody.chest("home"), Actor.system("t"), null);
        store.transition(letter(), MailState.MISSING, Custody.NONE, Actor.system("t"), null);
        List<MailFact> facts = store.mail_facts(before, System.currentTimeMillis() + 1000L);
        assertEquals(List.of(MailState.AT_ORIGIN_BRANCH, MailState.DELIVERED, MailState.MISSING),
                facts.stream().map(MailFact::to).toList());
        MailFact delivered = facts.get(1);
        assertEquals("testville", delivered.origin_office());
        assertEquals("riverside", delivered.dest_office());
        assertEquals(MailState.AT_CENTRAL, delivered.from());
        assertEquals(facts.get(0).at(), delivered.picked_up_at()); // timed from the pickup
        assertEquals(MailState.POSTED, facts.get(2).from());
        assertEquals(1L, facts.get(2).picked_up_at()); // never picked up: from its creation
        assertTrue(store.mail_facts(0L, before).isEmpty());
    }

    @Test
    void officeStatsAreReplacedPerOfficeAndDay() {
        assertEquals(-1, store.last_stats_day());
        store.put_office_stats(List.of(new OfficeDay("main", "testville", 100, 1, 2, 1, 0, 500L),
                new OfficeDay("main", "riverside", 100, 0, 1, 1, 1, 50L)));
        store.put_office_stats(List.of(new OfficeDay("main", "testville", 100, 3, 4, 4, 0, 900L),
                new OfficeDay("main", "testville", 101, 1, 0, 0, 0, 0L)));
        assertEquals(101, store.last_stats_day());
        List<OfficeDay> rows = store.office_stats(100, 100);
        assertEquals(List.of(new OfficeDay("main", "riverside", 100, 0, 1, 1, 1, 50L),
                new OfficeDay("main", "testville", 100, 3, 4, 4, 0, 900L)), rows);
        assertEquals(3, store.office_stats(0, 200).size());
        SqlMailStore other = new SqlMailStore(ds, "other", null, dialect());
        assertEquals(-1, other.last_stats_day()); // another server's rows are its own
        assertTrue(other.office_stats(0, 200).isEmpty());
    }

    /** A letter from this server ("main") to {@code dest_server}, waiting in the network, due there at {@code due}. */
    private MailRecord in_network_to(String dest_server, long due) {
        MailRecord r = store.create(MailRecord.new_letter(UUID.randomUUID(), "main", dest_server, "Testville", "Riverside", "Mill",
                UUID.randomUUID(), null, "{}".getBytes(StandardCharsets.UTF_8), 4440, 1L, null), Actor.system("test"), "posted");
        MailRecord c = store.transition(r, MailState.AT_CENTRAL, Custody.chest("central"), Actor.system("t"), null);
        MailRecord moving = store.begin_move(c, MailState.IN_NETWORK, Custody.NONE, Actor.central(), null);
        return store.depart(moving, due, Actor.central(), "left");
    }

    @Test
    void lettersNobodyCollectedAreStranded() {
        MailRecord r = in_network_to("other", 1000L);
        assertEquals(List.of(r.id), store.stranded(1001L).stream().map(m -> m.id).toList());
        assertTrue(store.stranded(1000L).isEmpty()); // not yet overdue
        SqlMailStore other = new SqlMailStore(ds, "other", null, dialect());
        assertTrue(other.stranded(Long.MAX_VALUE).isEmpty()); // only the sender's server sees it as stranded
    }

    @Test
    void readdressSendsALetterBackAsTheSameRow() {
        MailRecord r = in_network_to("other", 1000L);
        byte[] payload = "{\"returned\":\"no such office\"}".getBytes(StandardCharsets.UTF_8);
        MailRecord back = store.readdress(r, "main", "Testville", "Home", payload, 2000L, Actor.central(), "returned");
        assertEquals(r.version + 1, back.version);
        assertEquals("main", back.dest_server);
        assertEquals("testville", back.dest_office);
        assertEquals("home", back.dest_address);
        assertEquals(MailState.IN_NETWORK, back.state);
        assertEquals(2000L, back.due_at);
        assertArrayEquals(payload, back.payload);
        assertEquals(List.of(back.id), store.in_network().stream().map(m -> m.id).toList()); // now for this server to claim
        assertTrue(store.stranded(Long.MAX_VALUE).isEmpty());
        assertThrows(ConflictException.class,
                () -> store.readdress(r, "main", "x", null, payload, 0L, Actor.central(), "stale")); // the old version
        assertEquals("returned", store.history(r.id).get(store.history(r.id).size() - 1).detail);
    }

    @Test
    void boxesTakeTheLowestFreeNumberPerOffice() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        assertEquals(1, store.rent_box("Testville", a, "Alex", 10L).number());
        assertEquals(2, store.rent_box("testville", b, "Blair", 11L).number());
        assertEquals(1, store.rent_box("riverside", c, "Casey", 12L).number()); // numbered per office
        assertTrue(store.release_box("testville", 1));
        assertFalse(store.release_box("testville", 1));
        assertEquals(1, store.rent_box("testville", c, "Casey", 13L).number()); // a freed number is reused
        assertEquals(3, store.rent_box("testville", a, "Alex", 14L).number());
        assertEquals(List.of("riverside#1", "testville#1"), store.boxes_of(c).stream().map(x -> x.office() + "#" + x.number()).toList());
        assertEquals(List.of(1, 2, 3), store.boxes("testville").stream().map(PoBox::number).toList());
        assertEquals(4, store.boxes(null).size());
    }

    @Test
    void aBoxKeepsItsArrearsAndIsThisServersOwn() {
        UUID a = UUID.randomUUID();
        store.rent_box("testville", a, "Alex", 10L);
        store.set_box_arrears("testville", 1, 12.5, 2);
        PoBox box = store.box("testville", 1).orElseThrow();
        assertEquals(new PoBox("main", "testville", 1, a, "Alex", 10L, 12.5, 2), box);
        assertTrue(store.box("testville", 2).isEmpty());
        SqlMailStore other = new SqlMailStore(ds, "other", null, dialect());
        assertTrue(other.boxes(null).isEmpty());
        assertEquals(1, other.rent_box("testville", a, "Alex", 20L).number()); // another server's Testville
    }

    @Test
    void boxMailIsFoundByItsBox() {
        MailRecord r = letter();
        MailRecord filed = store.transition(r, MailState.DELIVERED, Custody.box("Testville", 3), Actor.postman("testville"), "filed");
        assertEquals(Custody.box("testville", 3), filed.custody);
        assertEquals(List.of(r.id), store.in_box("testville", 3).stream().map(m -> m.id).toList());
        assertTrue(store.in_box("testville", 4).isEmpty());
        assertEquals(List.of(r.id), store.in_boxes().stream().map(m -> m.id).toList());
        assertTrue(new SqlMailStore(ds, "other", null, dialect()).in_boxes().isEmpty()); // this server's boxes only
        assertEquals(Custody.box("testville", 3), store.get(r.id).orElseThrow().custody); // read back from the row
        store.transition(filed, MailState.DELIVERED, Custody.NONE, Actor.system("t"), "taken");
        assertTrue(store.in_box("testville", 3).isEmpty());
    }
}
