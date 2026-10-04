package com.vodhanel.minecraft.va_postal.store;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link MailStore} over JDBC, in the SQL subset SQLite and MySQL/MariaDB share, so switching backends is a
 * config change (docs/design/persistent-state.md §4, §5). Calls are synchronous; callers decide the thread.
 */
public final class SqlMailStore implements MailStore {
    /** Schema migrations, applied in order and recorded in {@code schema_version}. */
    static final String[] MIGRATIONS = {"V1__init.sql"};

    private static final String COLUMNS = "mail_id, kind, state, version, origin_server, dest_server, origin_office, "
            + "dest_office, dest_address, custody_server, custody_kind, custody_ref, pending_state, pending_kind, "
            + "pending_ref, sender_uuid, attention_uuid, cod_amount, postage_paid, payload_format, payload, "
            + "mc_data_version, created_at, updated_at";

    private final DataSource source;
    private final String server_id;
    private final AutoCloseable closer;

    /**
     * @param closer closed with the store (the connection pool), or null
     */
    public SqlMailStore(DataSource source, String server_id, AutoCloseable closer) {
        this.source = source;
        this.server_id = server_id;
        this.closer = closer;
        migrate();
    }

    @Override
    public String server_id() {
        return server_id;
    }

    // ---- Schema ----------------------------------------------------------------------------

    private void migrate() {
        try (Connection c = source.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)");
            }
            int current = 0;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
                if (rs.next()) {
                    current = rs.getInt(1);
                }
            }
            for (int v = current + 1; v <= MIGRATIONS.length; v++) {
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    for (String sql : statements(read_migration(MIGRATIONS[v - 1]))) {
                        st.executeUpdate(sql);
                    }
                    st.executeUpdate("INSERT INTO schema_version (version) VALUES (" + v + ")");
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        } catch (SQLException | IOException e) {
            throw new StoreException("Could not migrate the mail store", e);
        }
    }

    private static String read_migration(String name) throws IOException {
        try (InputStream in = SqlMailStore.class.getResourceAsStream("/db/" + name)) {
            if (in == null) {
                throw new IOException("Missing migration " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Splits a migration into statements, dropping {@code --} comments. */
    static List<String> statements(String script) {
        StringBuilder clean = new StringBuilder();
        for (String line : script.split("\n")) {
            int comment = line.indexOf("--");
            clean.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : clean.toString().split(";")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    // ---- Records ---------------------------------------------------------------------------

    @Override
    public MailRecord create(MailRecord r, Actor actor, String detail) {
        String sql = "INSERT INTO mail (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int i = 1;
                    ps.setString(i++, r.id.toString());
                    ps.setString(i++, r.kind.name());
                    ps.setString(i++, r.state.name());
                    ps.setInt(i++, r.version);
                    ps.setString(i++, r.origin_server);
                    ps.setString(i++, r.dest_server);
                    ps.setString(i++, r.origin_office);
                    ps.setString(i++, r.dest_office);
                    ps.setString(i++, r.dest_address);
                    ps.setString(i++, r.custody_server);
                    ps.setString(i++, r.custody.kind.name());
                    ps.setString(i++, r.custody.ref);
                    ps.setNull(i++, Types.VARCHAR);
                    ps.setNull(i++, Types.VARCHAR);
                    ps.setNull(i++, Types.VARCHAR);
                    ps.setString(i++, str(r.sender));
                    ps.setString(i++, str(r.attention));
                    ps.setBigDecimal(i++, BigDecimal.valueOf(r.cod_amount));
                    ps.setBigDecimal(i++, BigDecimal.valueOf(r.postage_paid));
                    ps.setString(i++, r.payload_format);
                    ps.setBytes(i++, r.payload);
                    ps.setInt(i++, r.mc_data_version);
                    ps.setLong(i++, r.created_at);
                    ps.setLong(i++, r.updated_at);
                    ps.executeUpdate();
                }
                event(c, r.id, r.version, null, r.state, actor, detail);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreException("Could not create mail " + r.id, e);
        }
        return get(r.id).orElseThrow();
    }

    @Override
    public Optional<MailRecord> get(UUID id) {
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM mail WHERE mail_id = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("Could not read mail " + id, e);
        }
    }

    @Override
    public MailRecord transition(MailRecord current, MailState to, Custody custody, Actor actor, String detail) {
        if (current.moving()) {
            throw new ConflictException(current.id + " has a move in flight; commit or cancel it first");
        }
        return update(current, to, custody, null, null, actor, detail);
    }

    @Override
    public MailRecord begin_move(MailRecord current, MailState to, Custody to_custody, Actor actor, String detail) {
        if (current.moving()) {
            throw new ConflictException(current.id + " already has a move in flight");
        }
        if (current.state.terminal()) {
            throw new ConflictException(current.id + " is " + current.state + "; it can't move");
        }
        if (to == MailState.IN_NETWORK && current.kind != MailKind.LETTER) {
            throw new ConflictException("Only letters can cross servers (" + current.id + " is a " + current.kind + ")");
        }
        return update(current, current.state, current.custody, to, to_custody, actor,
                "move to " + to + "@" + to_custody + (detail == null ? "" : ": " + detail));
    }

    @Override
    public MailRecord commit_move(MailRecord current, Actor actor, String detail) {
        if (!current.moving()) {
            throw new ConflictException(current.id + " has no move in flight");
        }
        return update(current, current.pending_state, current.pending_custody, null, null, actor, detail);
    }

    @Override
    public MailRecord cancel_move(MailRecord current, Actor actor, String detail) {
        if (!current.moving()) {
            throw new ConflictException(current.id + " has no move in flight");
        }
        return update(current, current.state, current.custody, null, null, actor,
                "move cancelled" + (detail == null ? "" : ": " + detail));
    }

    /** The one write path: version-checked update of state, custody and pending move, plus its event. */
    private MailRecord update(MailRecord current, MailState state, Custody custody, MailState pending_state,
                              Custody pending_custody, Actor actor, String detail) {
        if (state == MailState.IN_NETWORK && current.kind != MailKind.LETTER) {
            throw new ConflictException("Only letters can cross servers (" + current.id + " is a " + current.kind + ")");
        }
        long now = System.currentTimeMillis();
        String sql = "UPDATE mail SET state = ?, version = ?, custody_server = ?, custody_kind = ?, custody_ref = ?, "
                + "pending_state = ?, pending_kind = ?, pending_ref = ?, updated_at = ? WHERE mail_id = ? AND version = ?";
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                int rows;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int i = 1;
                    ps.setString(i++, state.name());
                    ps.setInt(i++, current.version + 1);
                    ps.setString(i++, server_id);
                    ps.setString(i++, custody.kind.name());
                    ps.setString(i++, custody.ref);
                    ps.setString(i++, pending_state == null ? null : pending_state.name());
                    ps.setString(i++, pending_custody == null ? null : pending_custody.kind.name());
                    ps.setString(i++, pending_custody == null ? null : pending_custody.ref);
                    ps.setLong(i++, now);
                    ps.setString(i++, current.id.toString());
                    ps.setInt(i++, current.version);
                    rows = ps.executeUpdate();
                }
                if (rows != 1) {
                    c.rollback();
                    throw new ConflictException(current.id + " changed since version " + current.version);
                }
                event(c, current.id, current.version + 1, current.state, pending_state != null ? pending_state : state, actor, detail);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreException("Could not update mail " + current.id, e);
        }
        return get(current.id).orElseThrow();
    }

    private void event(Connection c, UUID id, int version, MailState from, MailState to, Actor actor, String detail)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO mail_event (mail_id, version, at, server_id, "
                + "from_state, to_state, actor_kind, actor_ref, detail) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id.toString());
            ps.setInt(2, version);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, server_id);
            ps.setString(5, from == null ? null : from.name());
            ps.setString(6, to.name());
            ps.setString(7, actor.kind);
            ps.setString(8, actor.ref);
            ps.setString(9, detail == null ? null : (detail.length() > 255 ? detail.substring(0, 255) : detail));
            ps.executeUpdate();
        }
    }

    // ---- Queries ---------------------------------------------------------------------------

    @Override
    public List<MailRecord> held_here() {
        return query("SELECT " + COLUMNS + " FROM mail WHERE custody_server = ? AND (custody_kind <> 'NONE' OR "
                + "pending_state IS NOT NULL) AND state NOT IN ('DELIVERED','RETURNED','REFUSED','EXPIRED','CLAIMED')", server_id);
    }

    @Override
    public List<MailRecord> by_destination(MailState state, String office) {
        return query("SELECT " + COLUMNS + " FROM mail WHERE custody_server = ? AND state = ? AND dest_office = ?",
                server_id, state.name(), MailRecord.lower(office));
    }

    private List<MailRecord> query(String sql, String... args) {
        List<MailRecord> out = new ArrayList<>();
        try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Mail query failed", e);
        }
        return out;
    }

    @Override
    public List<MailRecord> recent(int limit) {
        return query("SELECT " + COLUMNS + " FROM mail WHERE origin_server = ? ORDER BY created_at DESC LIMIT "
                + Math.max(1, Math.min(100, limit)), server_id);
    }

    @Override
    public List<MailEvent> history(UUID id) {
        List<MailEvent> out = new ArrayList<>();
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT mail_id, version, at, server_id, from_state, to_state, "
                     + "actor_kind, actor_ref, detail FROM mail_event WHERE mail_id = ? ORDER BY version")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MailEvent(UUID.fromString(rs.getString(1)), rs.getInt(2), rs.getLong(3), rs.getString(4),
                            state(rs.getString(5)), state(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getString(9)));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Could not read history of " + id, e);
        }
        return out;
    }

    // ---- Route runs ------------------------------------------------------------------------

    @Override
    public void start_run(String run_id, String office, String address, String npc_ref, long now) {
        exec("INSERT INTO route_run (run_id, server_id, office, address, npc_ref, started_at, waypoint, direction) "
                + "VALUES (?,?,?,?,?,?,0,'OUT')", run_id, server_id, MailRecord.lower(office), MailRecord.lower(address),
                npc_ref, now);
    }

    @Override
    public void end_run(String run_id) {
        exec("DELETE FROM route_run WHERE run_id = ?", run_id);
    }

    @Override
    public List<String[]> runs() {
        List<String[]> out = new ArrayList<>();
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT run_id, office, address FROM route_run WHERE server_id = ?")) {
            ps.setString(1, server_id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Could not read route runs", e);
        }
        return out;
    }

    private void exec(String sql, Object... args) {
        try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("Mail store write failed", e);
        }
    }

    // ---- Mapping ---------------------------------------------------------------------------

    private static MailRecord read(ResultSet rs) throws SQLException {
        return new MailRecord(UUID.fromString(rs.getString("mail_id")), MailKind.valueOf(rs.getString("kind")),
                MailState.valueOf(rs.getString("state")), rs.getInt("version"), rs.getString("origin_server"),
                rs.getString("dest_server"), rs.getString("origin_office"), rs.getString("dest_office"),
                rs.getString("dest_address"), rs.getString("custody_server"),
                Custody.of(rs.getString("custody_kind"), rs.getString("custody_ref")),
                state(rs.getString("pending_state")),
                rs.getString("pending_kind") == null ? null : Custody.of(rs.getString("pending_kind"), rs.getString("pending_ref")),
                uuid(rs.getString("sender_uuid")), uuid(rs.getString("attention_uuid")),
                rs.getBigDecimal("cod_amount").doubleValue(), rs.getBigDecimal("postage_paid").doubleValue(),
                rs.getString("payload_format"), rs.getBytes("payload"), rs.getInt("mc_data_version"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private static MailState state(String s) {
        return s == null ? null : MailState.valueOf(s);
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    @Override
    public void close() {
        if (closer != null) {
            try {
                closer.close();
            } catch (Exception ignored) {
            }
        }
    }
}
