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
    static final String[] MIGRATIONS = {"V1__init.sql", "V2__hold.sql", "V3__wide_payload.sql", "V4__directory.sql",
            "V5__network_player.sql", "V6__deed.sql"};

    private static final String COLUMNS = "mail_id, kind, state, version, origin_server, dest_server, origin_office, "
            + "dest_office, dest_address, custody_server, custody_kind, custody_ref, pending_state, pending_kind, "
            + "pending_ref, sender_uuid, attention_uuid, cod_amount, postage_paid, payload_format, payload, "
            + "mc_data_version, created_at, updated_at, hold_id";

    /** What a read returns: the written columns, plus the arrival time a letter in the network was given. */
    private static final String READ_COLUMNS = COLUMNS + ", due_at";

    private final DataSource source;
    private final String server_id;
    private final AutoCloseable closer;
    private final Dialect dialect;

    /**
     * @param closer closed with the store (the connection pool), or null
     */
    public SqlMailStore(DataSource source, String server_id, AutoCloseable closer) {
        this(source, server_id, closer, Dialect.SQLITE);
    }

    public SqlMailStore(DataSource source, String server_id, AutoCloseable closer, Dialect dialect) {
        this.source = source;
        this.server_id = server_id;
        this.closer = closer;
        this.dialect = dialect;
        migrate();
    }

    @Override
    public Dialect dialect() {
        return dialect;
    }

    @Override
    public int schema_version() {
        try (Connection c = source.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new StoreException("Could not read the schema version", e);
        }
    }

    @Override
    public String server_id() {
        return server_id;
    }

    // ---- Schema ----------------------------------------------------------------------------

    private void migrate() {
        try (Connection c = source.getConnection()) {
            // Servers sharing a database may start together: one migrates while the others wait, then find the
            // schema current. (MySQL commits DDL at once, so a transaction can't keep them apart.)
            if (dialect == Dialect.MYSQL) {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT GET_LOCK('postal_migrate', 120)")) {
                    if (!rs.next() || rs.getInt(1) != 1) {
                        throw new SQLException("Timed out waiting for another server to finish migrating the mail store");
                    }
                }
            }
            try {
                migrate(c);
            } finally {
                if (dialect == Dialect.MYSQL) {
                    try (Statement st = c.createStatement()) {
                        st.execute("DO RELEASE_LOCK('postal_migrate')");
                    }
                }
            }
        } catch (SQLException | IOException e) {
            throw new StoreException("Could not migrate the mail store", e);
        }
    }

    private void migrate(Connection c) throws SQLException, IOException {
        {
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
                    for (String sql : statements(read_migration(MIGRATIONS[v - 1], dialect))) {
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
        }
    }

    /** A migration's script: its dialect-specific variant ({@code V3__x.mysql.sql}) if there is one. */
    static String read_migration(String name, Dialect dialect) throws IOException {
        String variant = name.replace(".sql", "." + dialect.suffix + ".sql");
        try (InputStream in = SqlMailStore.class.getResourceAsStream("/db/" + variant)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
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
        String sql = "INSERT INTO mail (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
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
                    ps.setString(i++, r.hold_id);
                    ps.executeUpdate();
                }
                event(c, r.id, r.version, null, r.state, r.custody, actor, detail);
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
             PreparedStatement ps = c.prepareStatement("SELECT " + READ_COLUMNS + " FROM mail WHERE mail_id = ?")) {
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
    public MailRecord set_terms(MailRecord current, double cod_amount, String hold_id, Actor actor, String detail) {
        long now = System.currentTimeMillis();
        String sql = "UPDATE mail SET cod_amount = ?, hold_id = ?, version = ?, updated_at = ? WHERE mail_id = ? AND version = ?";
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                int rows;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setBigDecimal(1, BigDecimal.valueOf(cod_amount));
                    ps.setString(2, hold_id);
                    ps.setInt(3, current.version + 1);
                    ps.setLong(4, now);
                    ps.setString(5, current.id.toString());
                    ps.setInt(6, current.version);
                    rows = ps.executeUpdate();
                }
                if (rows != 1) {
                    c.rollback();
                    throw new ConflictException(current.id + " changed since version " + current.version);
                }
                event(c, current.id, current.version + 1, current.state, current.state, current.custody, actor, detail);
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

    @Override
    public MailRecord set_attention(MailRecord current, UUID attention, byte[] payload, Actor actor, String detail) {
        long now = System.currentTimeMillis();
        String sql = "UPDATE mail SET attention_uuid = ?, " + (payload == null ? "" : "payload = ?, ")
                + "version = ?, updated_at = ? WHERE mail_id = ? AND version = ?";
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                int rows;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int i = 1;
                    ps.setString(i++, str(attention));
                    if (payload != null) {
                        ps.setBytes(i++, payload);
                    }
                    ps.setInt(i++, current.version + 1);
                    ps.setLong(i++, now);
                    ps.setString(i++, current.id.toString());
                    ps.setInt(i, current.version);
                    rows = ps.executeUpdate();
                }
                if (rows != 1) {
                    c.rollback();
                    throw new ConflictException(current.id + " changed since version " + current.version);
                }
                event(c, current.id, current.version + 1, current.state, current.state, current.custody, actor, detail);
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
        if (to == MailState.IN_NETWORK && server_id.equals(current.dest_server)) {
            throw new ConflictException(current.id + " is addressed to this server; it doesn't go through the network");
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
        return update(current, state, custody, pending_state, pending_custody, actor, detail, "", null);
    }

    /** {@code guard}: extra conditions on the row ({@code AND ...}), with this server's id as its only parameter. */
    private MailRecord update(MailRecord current, MailState state, Custody custody, MailState pending_state,
                              Custody pending_custody, Actor actor, String detail, String guard, Long due_at) {
        if (state == MailState.IN_NETWORK && current.kind != MailKind.LETTER) {
            throw new ConflictException("Only letters can cross servers (" + current.id + " is a " + current.kind + ")");
        }
        long now = System.currentTimeMillis();
        String sql = "UPDATE mail SET state = ?, version = ?, custody_server = ?, custody_kind = ?, custody_ref = ?, "
                + "pending_state = ?, pending_kind = ?, pending_ref = ?, updated_at = ?" + (due_at == null ? "" : ", due_at = ?")
                + " WHERE mail_id = ? AND version = ?" + guard;
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
                    if (due_at != null) {
                        ps.setLong(i++, due_at);
                    }
                    ps.setString(i++, current.id.toString());
                    ps.setInt(i++, current.version);
                    if (!guard.isEmpty()) {
                        ps.setString(i++, server_id);
                    }
                    rows = ps.executeUpdate();
                }
                if (rows != 1) {
                    c.rollback();
                    throw new ConflictException(current.id + " changed since version " + current.version);
                }
                event(c, current.id, current.version + 1, current.state, pending_state != null ? pending_state : state,
                        custody, actor, detail);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreException("Could not update mail " + current.id, e);
        }
        // The row is now exactly this: what the UPDATE set, the rest unchanged. Building it here saves reading it
        // back, a round trip on every move (a database on another machine pays it each time).
        return new MailRecord(current.id, current.kind, state, current.version + 1, current.origin_server,
                current.dest_server, current.origin_office, current.dest_office, current.dest_address, server_id, custody,
                pending_state, pending_custody, current.sender, current.attention, current.cod_amount,
                current.postage_paid, current.payload_format, current.payload, current.mc_data_version,
                current.created_at, now, current.hold_id, due_at == null ? current.due_at : due_at);
    }

    private void event(Connection c, UUID id, int version, MailState from, MailState to, Custody custody, Actor actor,
                       String detail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO mail_event (mail_id, version, at, server_id, "
                + "from_state, to_state, actor_kind, actor_ref, detail, custody_kind, custody_ref) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id.toString());
            ps.setInt(2, version);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, server_id);
            ps.setString(5, from == null ? null : from.name());
            ps.setString(6, to.name());
            ps.setString(7, actor.kind);
            ps.setString(8, actor.ref);
            ps.setString(9, detail == null ? null : (detail.length() > 255 ? detail.substring(0, 255) : detail));
            ps.setString(10, custody.kind.name());
            ps.setString(11, custody.ref);
            ps.executeUpdate();
        }
    }

    // ---- Queries ---------------------------------------------------------------------------

    @Override
    public List<MailRecord> held_here() {
        return query("SELECT " + READ_COLUMNS + " FROM mail WHERE custody_server = ? AND (custody_kind <> 'NONE' OR "
                + "pending_state IS NOT NULL) AND state NOT IN ('DELIVERED','RETURNED','REFUSED','ACCEPTED','EXPIRED','CLAIMED','RECOVERED')", server_id);
    }

    @Override
    public List<MailRecord> by_destination(MailState state, String office) {
        // dest_server too: a letter waiting here for the network can share an office name with one of ours.
        return query("SELECT " + READ_COLUMNS + " FROM mail WHERE custody_server = ? AND state = ? AND dest_office = ? AND dest_server = ?",
                server_id, state.name(), MailRecord.lower(office), server_id);
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
    public Custody previous_chest(MailRecord current) {
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT custody_kind, custody_ref FROM mail_event WHERE mail_id = ? "
                     + "AND version < ? ORDER BY version DESC")) {
            ps.setString(1, current.id.toString());
            ps.setInt(2, current.version);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Custody k = Custody.of(rs.getString(1), rs.getString(2));
                    if (k.kind == Custody.Kind.CHEST && !k.equals(current.custody)) {
                        return k;
                    }
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Could not read the history of " + current.id, e);
        }
        return null;
    }

    @Override
    public List<MailRecord> delivered_since(long since_millis) {
        List<MailRecord> out = new ArrayList<>();
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + READ_COLUMNS + " FROM mail WHERE custody_server = ? "
                     + "AND state = 'DELIVERED' AND updated_at >= ?")) {
            ps.setString(1, server_id);
            ps.setLong(2, since_millis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Could not read delivered mail", e);
        }
        return out;
    }

    @Override
    public List<MailRecord> recent(int limit) {
        return query("SELECT " + READ_COLUMNS + " FROM mail WHERE origin_server = ? OR custody_server = ? ORDER BY updated_at DESC LIMIT "
                + Math.max(1, Math.min(100, limit)), server_id, server_id);
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

    // ---- Deeds -----------------------------------------------------------------------------

    private static final String DEED_COLUMNS = "deed_id, kind, state, holder_uuid, holder_name, server_id, office, name, "
            + "name_lower, price, issue_no, created_at, expires_at, updated_at";

    @Override
    public Deed create_deed(Deed d) {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (name_reserved(c, d.kind(), d.office(), d.name())) {
                    c.rollback();
                    throw new ConflictException("The name " + d.name() + " is already taken");
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO deed (" + DEED_COLUMNS
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, d.id().toString());
                    ps.setString(2, d.kind().name());
                    ps.setString(3, d.state().name());
                    ps.setString(4, d.holder().toString());
                    ps.setString(5, d.holder_name());
                    ps.setString(6, d.server_id());
                    ps.setString(7, d.office());
                    ps.setString(8, d.name());
                    ps.setString(9, d.name().toLowerCase(java.util.Locale.ROOT));
                    ps.setBigDecimal(10, BigDecimal.valueOf(d.price()));
                    ps.setInt(11, d.issue());
                    ps.setLong(12, d.created_at());
                    ps.setLong(13, d.expires_at());
                    ps.setLong(14, d.updated_at());
                    ps.executeUpdate();
                }
                c.commit();
                return d;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException("Could not record deed " + d.id(), e);
        }
    }

    private static boolean name_reserved(Connection c, Deed.Kind kind, String office, String name) throws SQLException {
        // A charter's name is the office it founds: unique across the network. An address is unique in its office.
        String sql = "SELECT COUNT(*) FROM deed WHERE kind = ? AND name_lower = ? AND state IN ('PENDING', 'ISSUED')"
                + (kind == Deed.Kind.ADDRESS ? " AND office = ?" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, kind.name());
            ps.setString(2, name.toLowerCase(java.util.Locale.ROOT));
            if (kind == Deed.Kind.ADDRESS) {
                ps.setString(3, office);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    @Override
    public Optional<Deed> deed(UUID id) {
        return deeds("WHERE deed_id = ?", id.toString()).stream().findFirst();
    }

    @Override
    public boolean deed_state(UUID id, Deed.State from, Deed.State to, long now) {
        try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE deed SET state = ?, updated_at = ? WHERE deed_id = ? AND state = ?")) {
            ps.setString(1, to.name());
            ps.setLong(2, now);
            ps.setString(3, id.toString());
            ps.setString(4, from.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new StoreException("Could not update deed " + id, e);
        }
    }

    @Override
    public Optional<Deed> reissue_deed(UUID id, long now) {
        try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE deed SET issue_no = issue_no + 1, updated_at = ? WHERE deed_id = ? AND state IN ('PENDING', 'ISSUED')")) {
            ps.setLong(1, now);
            ps.setString(2, id.toString());
            if (ps.executeUpdate() != 1) {
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("Could not reissue deed " + id, e);
        }
        return deed(id);
    }

    @Override
    public boolean deed_name_reserved(Deed.Kind kind, String office, String name) {
        try (Connection c = source.getConnection()) {
            return name_reserved(c, kind, office, name);
        } catch (SQLException e) {
            throw new StoreException("Could not read deeds", e);
        }
    }

    @Override
    public List<Deed> deeds_of(UUID holder) {
        return deeds("WHERE holder_uuid = ? ORDER BY created_at DESC", holder.toString());
    }

    @Override
    public List<Deed> deeds_due(long now) {
        List<Deed> out = new ArrayList<>();
        for (Deed d : deeds("WHERE server_id = ? AND state IN ('PENDING', 'ISSUED') ORDER BY expires_at", server_id)) {
            if (d.expires_at() <= now) {
                out.add(d);
            }
        }
        return out;
    }

    @Override
    public double deeds_held() {
        try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT SUM(price) FROM deed WHERE server_id = ? AND state IN ('PENDING', 'ISSUED')")) {
            ps.setString(1, server_id);
            try (ResultSet rs = ps.executeQuery()) {
                BigDecimal sum = rs.next() ? rs.getBigDecimal(1) : null;
                return sum == null ? 0.0D : sum.doubleValue();
            }
        } catch (SQLException e) {
            throw new StoreException("Could not read deeds", e);
        }
    }

    private List<Deed> deeds(String where, String arg) {
        List<Deed> out = new ArrayList<>();
        try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + DEED_COLUMNS + " FROM deed " + where)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Deed(UUID.fromString(rs.getString(1)), Deed.Kind.valueOf(rs.getString(2)),
                            Deed.State.valueOf(rs.getString(3)), UUID.fromString(rs.getString(4)), rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(10).doubleValue(),
                            rs.getInt(11), rs.getLong(12), rs.getLong(13), rs.getLong(14)));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Could not read deeds", e);
        }
        return out;
    }

    // ---- Network ---------------------------------------------------------------------------

    @Override
    public UUID heartbeat(UUID instance, long started_at, long now, boolean first) {
        UUID other = null;
        try (Connection c = source.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT instance FROM postal_server WHERE server_id = ?")) {
                ps.setString(1, server_id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && !first && !instance.toString().equals(rs.getString(1))) {
                        other = UUID.fromString(rs.getString(1));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE postal_server SET instance = ?, started_at = ?, last_seen = ? WHERE server_id = ?")) {
                ps.setString(1, instance.toString());
                ps.setLong(2, started_at);
                ps.setLong(3, now);
                ps.setString(4, server_id);
                if (ps.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO postal_server (server_id, instance, started_at, last_seen) VALUES (?,?,?,?)")) {
                        ins.setString(1, server_id);
                        ins.setString(2, instance.toString());
                        ins.setLong(3, started_at);
                        ins.setLong(4, now);
                        ins.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Could not record this server", e);
        }
        return other;
    }

    @Override
    public void sign_off(UUID instance) {
        exec("UPDATE postal_server SET last_seen = 0 WHERE server_id = ? AND instance = ?", server_id, instance.toString());
        exec("UPDATE network_player SET online = 0 WHERE server_id = ?", server_id);
    }

    @Override
    public List<ServerInfo> servers() {
        List<ServerInfo> out = new ArrayList<>();
        try (Connection c = source.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT server_id, instance, started_at, last_seen FROM postal_server ORDER BY server_id")) {
            while (rs.next()) {
                out.add(new ServerInfo(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getLong(3), rs.getLong(4)));
            }
        } catch (SQLException e) {
            throw new StoreException("Could not read the servers", e);
        }
        return out;
    }

    /**
     * Two servers publishing at once (typically starting together) can deadlock on MySQL/MariaDB: under REPEATABLE
     * READ, each {@code DELETE ... WHERE server_id = ?} takes gap locks the other's inserts need. READ COMMITTED
     * takes no gap locks here, which avoids most of it (safe: the directory is rebuilt whole each time), and a
     * deadlock that still happens is retried, so only a publish that keeps failing counts as a failure.
     */
    @Override
    public void publish_directory(List<DirectoryEntry> entries, long now) {
        for (int attempt = 1; ; attempt++) {
            try {
                publish_directory_once(entries, now);
                return;
            } catch (StoreException e) {
                if (attempt >= DEADLOCK_RETRIES || !is_deadlock(e.getCause())) {
                    throw e;
                }
                try {
                    Thread.sleep(20L + java.util.concurrent.ThreadLocalRandom.current().nextLong(80L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static final int DEADLOCK_RETRIES = 3;

    /** A deadlock (MySQL/MariaDB error 1213, SQL state 40001): the transaction was rolled back and may be retried. */
    static boolean is_deadlock(Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && ("40001".equals(sql.getSQLState()) || sql.getErrorCode() == 1213)) {
                return true;
            }
        }
        return false;
    }

    private void publish_directory_once(List<DirectoryEntry> entries, long now) {
        try (Connection c = source.getConnection()) {
            int isolation = c.getTransactionIsolation();
            if (dialect == Dialect.MYSQL) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            }
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM directory_address WHERE server_id = ?")) {
                    ps.setString(1, server_id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM directory_office WHERE server_id = ?")) {
                    ps.setString(1, server_id);
                    ps.executeUpdate();
                }
                try (PreparedStatement office = c.prepareStatement("INSERT INTO directory_office (server_id, office, "
                        + "owner_uuid, is_open, is_central, location_key, updated_at) VALUES (?,?,?,?,?,?,?)");
                     PreparedStatement address = c.prepareStatement("INSERT INTO directory_address (server_id, office, "
                             + "address, owner_uuid, is_open, location_key, updated_at) VALUES (?,?,?,?,?,?,?)")) {
                    for (DirectoryEntry e : entries) {
                        if (e.is_office()) {
                            office.setString(1, server_id);
                            office.setString(2, e.office().toLowerCase());
                            office.setString(3, str(e.owner()));
                            office.setInt(4, e.open() ? 1 : 0);
                            office.setInt(5, e.central() ? 1 : 0);
                            office.setString(6, e.location());
                            office.setLong(7, now);
                            office.addBatch();
                        } else {
                            address.setString(1, server_id);
                            address.setString(2, e.office().toLowerCase());
                            address.setString(3, e.address().toLowerCase());
                            address.setString(4, str(e.owner()));
                            address.setInt(5, e.open() ? 1 : 0);
                            address.setString(6, e.location());
                            address.setLong(7, now);
                            address.addBatch();
                        }
                    }
                    office.executeBatch();
                    address.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
                if (dialect == Dialect.MYSQL) {
                    c.setTransactionIsolation(isolation); // pooled connections go back as they came
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Could not publish the directory", e);
        }
    }

    @Override
    public List<DirectoryEntry> directory(String server) {
        List<DirectoryEntry> out = new ArrayList<>();
        String where = server == null ? "" : " WHERE server_id = ?";
        try (Connection c = source.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT server_id, office, owner_uuid, is_open, is_central, "
                    + "location_key FROM directory_office" + where + " ORDER BY server_id, office")) {
                if (server != null) {
                    ps.setString(1, server);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new DirectoryEntry(rs.getString(1), rs.getString(2), null, uuid(rs.getString(3)),
                                rs.getInt(4) != 0, rs.getInt(5) != 0, rs.getString(6)));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT server_id, office, address, owner_uuid, is_open, "
                    + "location_key FROM directory_address" + where + " ORDER BY server_id, office, address")) {
                if (server != null) {
                    ps.setString(1, server);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new DirectoryEntry(rs.getString(1), rs.getString(2), rs.getString(3),
                                uuid(rs.getString(4)), rs.getInt(5) != 0, false, rs.getString(6)));
                    }
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Could not read the directory", e);
        }
        return out;
    }

    // ---- Cross-server letters --------------------------------------------------------------

    @Override
    public List<MailRecord> in_network() {
        return query("SELECT " + READ_COLUMNS + " FROM mail WHERE state = 'IN_NETWORK' AND dest_server = ? AND kind = 'LETTER' "
                + "AND pending_state IS NULL ORDER BY updated_at", server_id);
    }

    @Override
    public List<MailRecord> outbound(String chest) {
        return query("SELECT " + READ_COLUMNS + " FROM mail WHERE custody_server = ? AND state = 'AT_CENTRAL' AND dest_server <> ? "
                + "AND custody_kind = 'CHEST' AND custody_ref = ? AND pending_state IS NULL ORDER BY updated_at", server_id, server_id, chest);
    }

    @Override
    public MailRecord depart(MailRecord moving, long arrives_at, Actor actor, String detail) {
        if (!moving.moving() || moving.pending_state != MailState.IN_NETWORK) {
            throw new ConflictException(moving.id + " isn't being handed to the network");
        }
        return update(moving, MailState.IN_NETWORK, moving.pending_custody, null, null, actor, detail, "", arrives_at);
    }

    @Override
    public MailRecord claim(MailRecord current, Custody to, Actor actor) {
        if (current.kind != MailKind.LETTER || current.state != MailState.IN_NETWORK || current.moving()
                || !server_id.equals(current.dest_server)) {
            throw new ConflictException(current.id + " isn't a letter waiting in the network for " + server_id);
        }
        return update(current, current.state, current.custody, MailState.AT_CENTRAL, to, actor,
                "claimed from the network by " + server_id + ", move to AT_CENTRAL@" + to,
                " AND state = 'IN_NETWORK' AND kind = 'LETTER' AND pending_state IS NULL AND dest_server = ?", null);
    }

    @Override
    public void player_seen(UUID player, String name, boolean online, long now) {
        if (online) {
            try (Connection c = source.getConnection()) {
                upsert_player(c, player, name, now);
            } catch (SQLException e) {
                throw new StoreException("Could not record player " + name, e);
            }
        } else {
            // Only if they're still recorded here: switching servers can record the join before the quit.
            exec("UPDATE network_player SET online = 0, last_seen = ? WHERE player_uuid = ? AND server_id = ?",
                    now, player.toString(), server_id);
        }
    }

    @Override
    public void sync_players(List<NetworkPlayer> online, long now) {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("UPDATE network_player SET online = 0 WHERE server_id = ? AND online <> 0")) {
                    ps.setString(1, server_id);
                    ps.executeUpdate();
                }
                for (NetworkPlayer p : online) {
                    upsert_player(c, p.id(), p.name(), now);
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException("Could not record this server's players", e);
        }
    }

    private void upsert_player(Connection c, UUID player, String name, long now) throws SQLException {
        String n = name.length() > 16 ? name.substring(0, 16) : name;
        try (PreparedStatement ps = c.prepareStatement("UPDATE network_player SET name = ?, name_lower = ?, server_id = ?, "
                + "online = 1, last_seen = ? WHERE player_uuid = ?")) {
            ps.setString(1, n);
            ps.setString(2, n.toLowerCase(java.util.Locale.ROOT));
            ps.setString(3, server_id);
            ps.setLong(4, now);
            ps.setString(5, player.toString());
            if (ps.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO network_player (player_uuid, name, name_lower, server_id, "
                + "online, last_seen) VALUES (?,?,?,?,1,?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, n);
            ps.setString(3, n.toLowerCase(java.util.Locale.ROOT));
            ps.setString(4, server_id);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    @Override
    public List<NetworkPlayer> players_named(String name) {
        return players("WHERE name_lower = ? ORDER BY last_seen DESC", name.toLowerCase(java.util.Locale.ROOT).trim());
    }

    @Override
    public Optional<NetworkPlayer> player(UUID id) {
        return players("WHERE player_uuid = ?", id.toString()).stream().findFirst();
    }

    private List<NetworkPlayer> players(String where, String arg) {
        List<NetworkPlayer> out = new ArrayList<>();
        try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT player_uuid, name, server_id, online, last_seen FROM network_player " + where)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new NetworkPlayer(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                            rs.getInt(4) != 0, rs.getLong(5)));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Could not read players", e);
        }
        return out;
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
                rs.getLong("created_at"), rs.getLong("updated_at"), rs.getString("hold_id"), rs.getLong("due_at"));
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
