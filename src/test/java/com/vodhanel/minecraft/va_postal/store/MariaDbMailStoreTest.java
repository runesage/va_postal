package com.vodhanel.minecraft.va_postal.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mariadb.jdbc.MariaDbDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store contract on MySQL/MariaDB. Runs when {@code POSTAL_TEST_MYSQL_URL} names a database it may empty
 * (e.g. {@code jdbc:mariadb://localhost:3306/postal_test?user=postal&password=postal}); CI provides one.
 */
@EnabledIfEnvironmentVariable(named = "POSTAL_TEST_MYSQL_URL", matches = ".+")
class MariaDbMailStoreTest extends MailStoreContract {
    private static final String[] TABLES = {"mail_event", "mail", "route_run", "postal_server", "directory_office",
            "directory_address", "network_player", "deed", "schema_version"};

    @Override
    protected DataSource fresh_database() throws Exception {
        MariaDbDataSource mariadb = new MariaDbDataSource(System.getenv("POSTAL_TEST_MYSQL_URL"));
        try (Connection c = mariadb.getConnection(); Statement st = c.createStatement()) {
            for (String table : TABLES) {
                st.executeUpdate("DROP TABLE IF EXISTS " + table);
            }
        }
        return mariadb;
    }

    @Override
    protected Dialect dialect() {
        return Dialect.MYSQL;
    }

    /** Two servers publishing their directories at the same moment (as when they start together) both succeed. */
    @Test
    void twoServersPublishingAtOnceDontFail() throws Exception {
        SqlMailStore alpha = new SqlMailStore(ds, "alpha", null, dialect());
        SqlMailStore beta = new SqlMailStore(ds, "beta", null, dialect());
        int rounds = 40;
        CyclicBarrier together = new CyclicBarrier(2);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (SqlMailStore s : List.of(alpha, beta)) {
            String id = s == alpha ? "alpha" : "beta";
            Thread t = new Thread(() -> {
                for (int i = 0; i < rounds; i++) {
                    try {
                        together.await();
                        s.publish_directory(List.of(
                                new DirectoryEntry(id, "Testville", null, null, true, false, "world,20,-60,0"),
                                new DirectoryEntry(id, "Testville", "Home", null, true, false, "world,40,-60,0"),
                                new DirectoryEntry(id, "Central", null, null, true, true, "world,0,-60,0")), i);
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(120_000);
        }
        assertTrue(failures.isEmpty(), () -> failures.size() + " publishes failed, first: " + failures.peek());
        assertEquals(3, store.directory("alpha").size());
        assertEquals(3, store.directory("beta").size());
    }
}
