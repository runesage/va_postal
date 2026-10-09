package com.vodhanel.minecraft.va_postal.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only code that touches the mail database (docs/design/persistent-state.md §4). Every change is a
 * version-checked transition written together with its history event; a stale version raises
 * {@link ConflictException}.
 * <p>
 * Moves are two-phase so a crash can never dupe or lose mail: {@link #begin_move} records where the mail is
 * going, the caller changes the world, {@link #commit_move} makes it so. Reconciliation finishes or undoes a
 * begun move from the record.
 */
public interface MailStore extends AutoCloseable {
    /** This server's id ({@code Network.Server_id}). */
    String server_id();

    MailRecord create(MailRecord record, Actor actor, String detail);

    Optional<MailRecord> get(UUID id);

    /** A state change with no world move (e.g. to MISSING, or a change of custody already made). */
    MailRecord transition(MailRecord current, MailState to, Custody custody, Actor actor, String detail);

    /**
     * Changes a record's terms (COD amount, postage hold) without moving it; version-checked and recorded in
     * its history like a transition.
     */
    MailRecord set_terms(MailRecord current, double cod_amount, String hold_id, Actor actor, String detail);

    /** Phase 1 of a move: records the destination. The record keeps its current custody until committed. */
    MailRecord begin_move(MailRecord current, MailState to, Custody to_custody, Actor actor, String detail);

    /** Phase 3 of a move: the world now matches the pending destination. */
    MailRecord commit_move(MailRecord current, Actor actor, String detail);

    /** Undoes a begun move: the mail stays where it was. */
    MailRecord cancel_move(MailRecord current, Actor actor, String detail);

    /** Non-terminal records this server holds in chests or on routes, including any with a move in flight. */
    List<MailRecord> held_here();

    /** Records in {@code state} whose destination is {@code office} (lower-case match), on this server. */
    List<MailRecord> by_destination(MailState state, String office);

    List<MailEvent> history(UUID id);

    /** The last chest the mail was in before its current custody (null if none): where a rolled-back world has it. */
    Custody previous_chest(MailRecord current);

    /** Letters delivered on this server since {@code since_millis}. */
    List<MailRecord> delivered_since(long since_millis);

    /** The newest records on this server, newest first. */
    List<MailRecord> recent(int limit);

    void start_run(String run_id, String office, String address, String npc_ref, long now);

    void end_run(String run_id);

    /** Route runs this server has recorded as in progress: {run_id, office, address}. */
    List<String[]> runs();

    // ---- Network (persistent-state phase P3) ------------------------------------------------

    /** The database behind this store. */
    Dialect dialect();

    /** The applied schema version. */
    int schema_version();

    /**
     * Records that this server ({@code instance}, started {@code started_at}) is running. Returns the instance
     * that wrote this server id's row since this one last did, if any: another server is using the same id.
     * Null when all is well (and on the first call, which takes the row over from a server that stopped or
     * crashed).
     */
    UUID heartbeat(UUID instance, long started_at, long now, boolean first);

    /** Marks this server as stopped. */
    void sign_off(UUID instance);

    /** Every server that has used this database. */
    List<ServerInfo> servers();

    /** Replaces this server's directory rows (its offices and addresses) with {@code entries}, in one transaction. */
    void publish_directory(List<DirectoryEntry> entries, long now);

    /** The directory: every server's offices and addresses ({@code server_id} null), or one server's. */
    List<DirectoryEntry> directory(String server_id);

    // ---- Cross-server letters (persistent-state phase P4) -------------------------------------

    /** Letters waiting in the network for this server to claim, oldest first. */
    List<MailRecord> in_network();

    /** Letters this server holds at {@code chest} (its Central) that are bound for another server. */
    List<MailRecord> outbound(String chest);

    /**
     * Phase 1 of taking a letter from the network: only a {@code LETTER} in {@code IN_NETWORK} addressed to this
     * server matches, so of two claimers only one wins (the other gets {@link ConflictException}). The record
     * then belongs to this server, moving to {@code AT_CENTRAL} at {@code to}; commit once the book is there.
     */
    MailRecord claim(MailRecord current, Custody to, Actor actor);

    /** Records that {@code player} joined this server ({@code online}) or left it. */
    void player_seen(UUID player, String name, boolean online, long now);

    /** Marks this server's players offline, then {@code online} as on it (at startup, and after a reload). */
    void sync_players(List<NetworkPlayer> online, long now);

    /** Players whose name is {@code name} (any case), most recently seen first. */
    List<NetworkPlayer> players_named(String name);

    java.util.Optional<NetworkPlayer> player(UUID id);

    @Override
    void close();
}
