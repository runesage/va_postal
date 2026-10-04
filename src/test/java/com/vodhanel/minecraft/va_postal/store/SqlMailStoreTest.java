package com.vodhanel.minecraft.va_postal.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SqlMailStoreTest {
    @TempDir
    Path dir;
    private SQLiteDataSource ds;
    private SqlMailStore store;

    @BeforeEach
    void open() {
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("postal.db"));
        store = new SqlMailStore(ds, "main", null);
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
        store = new SqlMailStore(ds, "main", null);
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
    void migrationScriptSplitsAndDropsComments() {
        List<String> parts = SqlMailStore.statements("-- c\nCREATE TABLE a (x INT); -- trailing\nCREATE TABLE b (y INT);\n");
        assertEquals(List.of("CREATE TABLE a (x INT)", "CREATE TABLE b (y INT)"), parts);
    }
}
