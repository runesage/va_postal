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

    /**
     * Changes who a record is addressed to (the attention player; null for "[Resident]") without moving it.
     * {@code payload} replaces the stored book text so a book rebuilt from the record says the same; null keeps it.
     * Version-checked and recorded in its history like a transition.
     */
    MailRecord set_attention(MailRecord current, java.util.UUID attention, byte[] payload, Actor actor, String detail);

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
     * Phase 3 of handing a letter to the network ({@link #begin_move} to {@code IN_NETWORK}, the book out of
     * Central, then this): it's in the network, and arrives at {@code arrives_at} (epoch millis).
     */
    MailRecord depart(MailRecord moving, long arrives_at, Actor actor, String detail);

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

    // ---- Deeds (docs/design/deeds.md) ----------------------------------------------------------------

    /**
     * Records a new deed. An open deed (pending or issued) reserves its name: for an address, within its office;
     * for a charter, across the network. Raises {@link ConflictException} if an open deed already has the name.
     */
    Deed create_deed(Deed deed);

    java.util.Optional<Deed> deed(UUID id);

    /** Moves a deed from {@code from} to {@code to}; false if it wasn't in {@code from} (someone got there first). */
    boolean deed_state(UUID id, Deed.State from, Deed.State to, long now);

    /** Issues a pending deed (its application was approved), now good until {@code expires_at}; false if it wasn't pending. */
    boolean issue_deed(UUID id, long now, long expires_at);

    /** This server's deeds at {@code office} waiting for approval, oldest first. */
    List<Deed> deeds_pending(String office);

    /** This server's deeds of {@code kind} waiting for approval, oldest first (charters: they have no office yet). */
    List<Deed> deeds_pending(Deed.Kind kind);

    /** A new issue of an open deed's document; the older copies stop working. Empty if the deed isn't open. */
    java.util.Optional<Deed> reissue_deed(UUID id, long now);

    /** True if an open deed of {@code kind} reserves {@code name} at {@code office} (any office, for a charter). */
    boolean deed_name_reserved(Deed.Kind kind, String office, String name);

    /** A player's deeds, newest first. */
    List<Deed> deeds_of(UUID holder);

    /** This server's open deeds whose time ran out by {@code now}. */
    List<Deed> deeds_due(long now);

    /** The money this server's open deeds hold in Central's account. */
    double deeds_held();

    // ---- Office offers (docs/design/central-office.md §3) ----------------------------------------

    /** Records a new offer. Raises {@link ConflictException} if the office already has an open offer. */
    Offer create_offer(Offer offer);

    java.util.Optional<Offer> offer(UUID id);

    /** Moves an offer from {@code from} to {@code to}; false if it wasn't in {@code from} (someone got there first). */
    boolean offer_state(UUID id, Offer.State from, Offer.State to, long now);

    /** This server's open offer for {@code office}, if any. */
    java.util.Optional<Offer> open_offer(String office);

    /** This server's open offers to {@code buyer}, oldest first. */
    List<Offer> offers_to(UUID buyer);

    /** This server's open offers whose time ran out by {@code now}. */
    List<Offer> offers_due(long now);

    // ---- P.O. boxes (docs/design/po-boxes.md) ----------------------------------------------------------

    /** Rents the lowest free box number at {@code office} (this server) to {@code renter}; the new lease. */
    PoBox rent_box(String office, UUID renter, String renter_name, long now);

    /** This server's box {@code number} at {@code office}, if it's rented. */
    java.util.Optional<PoBox> box(String office, int number);

    /** This server's boxes rented by {@code renter}, by office and number. */
    List<PoBox> boxes_of(UUID renter);

    /** This server's boxes at {@code office} ({@code office} null: every office), by office and number. */
    List<PoBox> boxes(String office);

    /** Sets a lease's arrears and the days they've been owed. */
    void set_box_arrears(String office, int number, double arrears, int arrears_days);

    /** Ends a lease; false if the box wasn't rented. */
    boolean release_box(String office, int number);

    // ---- Office stats (docs/design/central-office.md §7) ---------------------------------------------

    /** This server's pickups, deliveries and losses in {@code [from, to)}, oldest first. */
    List<MailFact> mail_facts(long from, long to);

    /** Writes (replacing) these rows, all for this server. */
    void put_office_stats(List<OfficeDay> rows);

    /** This server's rows for days {@code from..to} inclusive. */
    List<OfficeDay> office_stats(int from, int to);

    /** The latest day this server has rows for, or -1. */
    int last_stats_day();

    @Override
    void close();
}
