package com.vodhanel.minecraft.va_postal.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Opens and holds the plugin's {@link MailStore} (docs/design/persistent-state.md §9). P1 supports SQLite,
 * one file in the plugin folder; MySQL/MariaDB arrives with P3 behind the same interface.
 */
public final class MailStores {
    private static MailStore active;

    private MailStores() {
    }

    /** The open store, or null if it couldn't be opened (mail then isn't tracked). */
    public static MailStore active() {
        return active;
    }

    public static synchronized MailStore open(File data_folder, String type, String server_id, Logger log) {
        close();
        if (!"sqlite".equalsIgnoreCase(type)) {
            log.warning("Storage.Type '" + type + "' isn't available yet (MySQL arrives in phase P3); using sqlite.");
        }
        try {
            data_folder.mkdirs();
            HikariConfig config = new HikariConfig();
            config.setPoolName("Postal-SQLite");
            config.setJdbcUrl("jdbc:sqlite:" + new File(data_folder, "postal.db").getAbsolutePath());
            // SQLite has a single writer: one connection, waiting briefly rather than failing when busy.
            config.setMaximumPoolSize(1);
            config.setConnectionInitSql("PRAGMA busy_timeout = 5000");
            HikariDataSource ds = new HikariDataSource(config);
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA synchronous = NORMAL");
            }
            active = new SqlMailStore(ds, server_id == null || server_id.isBlank() ? "main" : server_id.trim(), ds);
            log.info("Mail store: SQLite (" + new File(data_folder, "postal.db").getName() + "), server id '"
                    + active.server_id() + "'.");
        } catch (RuntimeException | LinkageError | java.sql.SQLException e) {
            log.severe("Could not open the mail store; mail will not be tracked: " + e);
            active = null;
        }
        return active;
    }

    public static synchronized void close() {
        if (active != null) {
            active.close();
            active = null;
        }
    }
}
