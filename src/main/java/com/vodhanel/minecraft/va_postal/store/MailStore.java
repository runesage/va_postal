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

    @Override
    void close();
}
