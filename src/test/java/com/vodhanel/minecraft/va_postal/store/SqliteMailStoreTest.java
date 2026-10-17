package com.vodhanel.minecraft.va_postal.store;

import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;

/** The store contract on SQLite (the single-server default). */
class SqliteMailStoreTest extends MailStoreContract {
    @TempDir
    Path dir;

    @Override
    protected DataSource fresh_database() {
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + dir.resolve("postal.db"));
        return sqlite;
    }

    @Override
    protected Dialect dialect() {
        return Dialect.SQLITE;
    }
}
