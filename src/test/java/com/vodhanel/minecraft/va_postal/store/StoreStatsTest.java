package com.vodhanel.minecraft.va_postal.store;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StoreStatsTest {
    /** A store whose every call fails while {@code down}, counting the calls that reach it. */
    private static MailStore flaky(AtomicBoolean down, AtomicInteger reached, boolean connection) {
        return (MailStore) Proxy.newProxyInstance(MailStore.class.getClassLoader(), new Class<?>[]{MailStore.class},
                (p, m, a) -> {
                    if (m.getName().equals("server_id")) {
                        return "main";
                    }
                    reached.incrementAndGet();
                    if (down.get()) {
                        throw new StoreException("Could not read mail", connection
                                ? new SQLTransientConnectionException("Connection is not available, request timed out")
                                : new SQLException("constraint", "23000"));
                    }
                    return m.getName().equals("schema_version") ? 4 : null;
                });
    }

    @Test
    void aLostConnectionFailsFastUntilTheDatabaseIsBack() throws Exception {
        StoreStats.probe_millis = 50L;
        AtomicBoolean down = new AtomicBoolean(true);
        AtomicInteger reached = new AtomicInteger();
        List<String> log = new CopyOnWriteArrayList<>();
        StoreStats stats = new StoreStats();
        MailStore store = StoreStats.timed(flaky(down, reached, true), stats, () -> true, log::add);

        assertThrows(StoreException.class, store::schema_version);
        assertEquals(1, reached.get());
        assertNotNull(stats.unavailable);
        // Tripped: calls fail at once, without reaching the database; local calls still work.
        for (int i = 0; i < 10; i++) {
            assertThrows(StoreException.class, () -> store.recent(5));
        }
        assertEquals("main", store.server_id());
        assertTrue(reached.get() <= 3, "calls reached the database while it was down: " + reached.get());
        // The database comes back: the background check lets calls through again.
        down.set(false);
        for (int i = 0; i < 100 && stats.unavailable != null; i++) {
            Thread.sleep(20L);
        }
        assertNull(stats.unavailable);
        assertEquals(4, store.schema_version());
        assertTrue(log.get(0).contains("can't be reached"));
        assertTrue(log.get(log.size() - 1).contains("is back"));
    }

    @Test
    void anOrdinaryErrorDoesNotTripTheBreaker() {
        AtomicBoolean down = new AtomicBoolean(true);
        AtomicInteger reached = new AtomicInteger();
        StoreStats stats = new StoreStats();
        MailStore store = StoreStats.timed(flaky(down, reached, false), stats, () -> false, msg -> { });
        assertThrows(StoreException.class, () -> store.recent(5));
        assertThrows(StoreException.class, () -> store.recent(5));
        assertEquals(2, reached.get());
        assertNull(stats.unavailable);
        assertEquals(2, stats.failures.get());
        assertEquals(0, stats.main_thread_calls.get());
    }
}
