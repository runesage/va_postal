package com.vodhanel.minecraft.va_postal.store;

/**
 * The database behind a {@link SqlMailStore}. The schema and queries are the subset both share; a migration
 * may have a dialect-specific variant ({@code V3__name.mysql.sql} beside {@code V3__name.sql}) where they
 * can't (column sizes).
 */
public enum Dialect {
    SQLITE("sqlite"),
    MYSQL("mysql");

    public final String suffix;

    Dialect(String suffix) {
        this.suffix = suffix;
    }
}
