package com.vodhanel.minecraft.va_postal.store;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mariadb.jdbc.MariaDbDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * The store contract on MySQL/MariaDB. Runs when {@code POSTAL_TEST_MYSQL_URL} names a database it may empty
 * (e.g. {@code jdbc:mariadb://localhost:3306/postal_test?user=postal&password=postal}); CI provides one.
 */
@EnabledIfEnvironmentVariable(named = "POSTAL_TEST_MYSQL_URL", matches = ".+")
class MariaDbMailStoreTest extends MailStoreContract {
    private static final String[] TABLES = {"mail_event", "mail", "route_run", "postal_server", "directory_office",
            "directory_address", "schema_version"};

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
}
