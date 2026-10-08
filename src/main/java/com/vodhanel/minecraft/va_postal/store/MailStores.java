package com.vodhanel.minecraft.va_postal.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Opens and holds the plugin's {@link MailStore} (docs/design/persistent-state.md §9): SQLite, one file in the
 * plugin folder (the single-server default), or MySQL/MariaDB, shared by every server of a network.
 */
public final class MailStores {
    private static MailStore active;
    private static StoreStats stats;
    private static String description = "not open";

    private MailStores() {
    }

    /** {@code Storage} and {@code Network.Server_id} from config.yml. */
    public record Settings(String type, String server_id, String host, int port, String database, String user,
                           String password, int pool_size, String properties) {
        boolean mysql() {
            return "mysql".equalsIgnoreCase(type) || "mariadb".equalsIgnoreCase(type);
        }
    }

    /** The open store, or null if it couldn't be opened (mail then isn't tracked). */
    public static MailStore active() {
        return active;
    }

    /** Timings of the open store's calls (null if none is open). */
    public static StoreStats stats() {
        return stats;
    }

    /** What's open, for /postal store: "SQLite (postal.db)" or "MySQL (host:port/db)". */
    public static String description() {
        return description;
    }

    public static synchronized MailStore open(File data_folder, Settings s, BooleanSupplier main_thread, Logger log) {
        close();
        String server_id = s.server_id() == null || s.server_id().isBlank() ? "main" : s.server_id().trim();
        try {
            HikariConfig config = new HikariConfig();
            Dialect dialect;
            if (s.mysql()) {
                dialect = Dialect.MYSQL;
                config.setPoolName("Postal-MySQL");
                config.setDriverClassName("org.mariadb.jdbc.Driver");
                String props = s.properties() == null || s.properties().isBlank() ? "" : "?" + s.properties().trim();
                config.setJdbcUrl("jdbc:mariadb://" + s.host() + ":" + s.port() + "/" + s.database() + props);
                config.setUsername(s.user());
                config.setPassword(s.password());
                config.setMaximumPoolSize(Math.max(2, s.pool_size()));
                // Short waits: a database that's down must not hang the server; calls fail and are retried later.
                config.setConnectionTimeout(3000L);
                config.setValidationTimeout(2000L);
                description = "MySQL (" + s.host() + ":" + s.port() + "/" + s.database() + ")";
                if ("main".equals(server_id)) {
                    log.warning("Storage.Type is mysql but Network.Server_id is 'main'. Fine for one server; every "
                            + "server sharing this database needs its own Server_id.");
                }
            } else {
                if (!"sqlite".equalsIgnoreCase(s.type())) {
                    log.warning("Unknown Storage.Type '" + s.type() + "'; using sqlite.");
                }
                dialect = Dialect.SQLITE;
                data_folder.mkdirs();
                config.setPoolName("Postal-SQLite");
                config.setJdbcUrl("jdbc:sqlite:" + new File(data_folder, "postal.db").getAbsolutePath());
                // SQLite has a single writer: one connection, waiting briefly rather than failing when busy.
                config.setMaximumPoolSize(1);
                config.setConnectionInitSql("PRAGMA busy_timeout = 5000");
                description = "SQLite (postal.db)";
            }
            HikariDataSource ds = new HikariDataSource(config);
            if (dialect == Dialect.SQLITE) {
                try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                    st.execute("PRAGMA journal_mode = WAL");
                    st.execute("PRAGMA synchronous = NORMAL");
                }
            }
            MailStore store = new SqlMailStore(ds, server_id, ds, dialect);
            stats = new StoreStats();
            active = StoreStats.timed(store, stats, main_thread, log::warning);
            log.info("Mail store: " + description + ", schema " + active.schema_version() + ", server id '"
                    + active.server_id() + "'.");
        } catch (RuntimeException | LinkageError | java.sql.SQLException e) {
            log.severe("Could not open the mail store (" + description + "); mail will not be tracked: " + e);
            active = null;
            stats = null;
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
