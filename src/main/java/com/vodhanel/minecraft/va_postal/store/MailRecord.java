package com.vodhanel.minecraft.va_postal.store;

import java.util.UUID;

/**
 * One row of {@code mail}: the authoritative state of a letter, parcel or distribution copy. Immutable; the
 * store returns a fresh record after every change.
 */
public final class MailRecord {
    public final UUID id;
    public final MailKind kind;
    public final MailState state;
    public final int version;
    public final String origin_server;
    public final String dest_server;
    public final String origin_office;
    public final String dest_office;
    public final String dest_address;
    public final String custody_server;
    public final Custody custody;
    /** A move begun but not committed: where it's going (null if none). */
    public final MailState pending_state;
    public final Custody pending_custody;
    public final UUID sender;
    public final UUID attention;
    public final double cod_amount;
    public final double postage_paid;
    public final String payload_format;
    public final byte[] payload;
    public final int mc_data_version;
    public final long created_at;
    public final long updated_at;
    /** The postage hold (economy escrow) that pays for this mail, or null. */
    public final String hold_id;

    public MailRecord(UUID id, MailKind kind, MailState state, int version, String origin_server, String dest_server,
                      String origin_office, String dest_office, String dest_address, String custody_server,
                      Custody custody, MailState pending_state, Custody pending_custody, UUID sender, UUID attention,
                      double cod_amount, double postage_paid, String payload_format, byte[] payload, int mc_data_version,
                      long created_at, long updated_at) {
        this(id, kind, state, version, origin_server, dest_server, origin_office, dest_office, dest_address,
                custody_server, custody, pending_state, pending_custody, sender, attention, cod_amount, postage_paid,
                payload_format, payload, mc_data_version, created_at, updated_at, null);
    }

    public MailRecord(UUID id, MailKind kind, MailState state, int version, String origin_server, String dest_server,
                      String origin_office, String dest_office, String dest_address, String custody_server,
                      Custody custody, MailState pending_state, Custody pending_custody, UUID sender, UUID attention,
                      double cod_amount, double postage_paid, String payload_format, byte[] payload, int mc_data_version,
                      long created_at, long updated_at, String hold_id) {
        this.hold_id = hold_id;
        this.id = id;
        this.kind = kind;
        this.state = state;
        this.version = version;
        this.origin_server = origin_server;
        this.dest_server = dest_server;
        this.origin_office = origin_office;
        this.dest_office = dest_office;
        this.dest_address = dest_address;
        this.custody_server = custody_server;
        this.custody = custody;
        this.pending_state = pending_state;
        this.pending_custody = pending_custody;
        this.sender = sender;
        this.attention = attention;
        this.cod_amount = cod_amount;
        this.postage_paid = postage_paid;
        this.payload_format = payload_format;
        this.payload = payload;
        this.mc_data_version = mc_data_version;
        this.created_at = created_at;
        this.updated_at = updated_at;
    }

    /** A new letter, posted on {@code server} by {@code sender}, not yet in any chest. */
    public static MailRecord new_letter(UUID id, String server, String origin_office, String dest_office,
                                        String dest_address, UUID sender, UUID attention, byte[] payload,
                                        int mc_data_version, long now) {
        return new_letter(id, server, origin_office, dest_office, dest_address, sender, attention, payload,
                mc_data_version, now, null);
    }

    public static MailRecord new_letter(UUID id, String server, String origin_office, String dest_office,
                                        String dest_address, UUID sender, UUID attention, byte[] payload,
                                        int mc_data_version, long now, String hold_id) {
        return new_letter(id, server, server, origin_office, dest_office, dest_address, sender, attention, payload,
                mc_data_version, now, hold_id);
    }

    /** A letter for an office on {@code dest_server} (another server's, for a letter that crosses the network). */
    public static MailRecord new_letter(UUID id, String server, String dest_server, String origin_office, String dest_office,
                                        String dest_address, UUID sender, UUID attention, byte[] payload,
                                        int mc_data_version, long now, String hold_id) {
        return new MailRecord(id, MailKind.LETTER, MailState.POSTED, 0, server, dest_server, lower(origin_office),
                lower(dest_office), lower(dest_address), server, Custody.NONE, null, null, sender, attention, 0.0D,
                0.0D, "LETTER_V1", payload, mc_data_version, now, now, hold_id);
    }

    /** A new parcel: its items are in the payload (Postal holds them), its label still with the sender. */
    public static MailRecord new_parcel(UUID id, String server, String origin_office, String dest_office,
                                        String dest_address, UUID sender, UUID attention, byte[] payload,
                                        int mc_data_version, long now, String hold_id) {
        return new MailRecord(id, MailKind.PARCEL, MailState.POSTED, 0, server, server, lower(origin_office),
                lower(dest_office), lower(dest_address), server, Custody.NONE, null, null, sender, attention, 0.0D,
                0.0D, "PARCEL_V1", payload, mc_data_version, now, now, hold_id);
    }

    /** True for a letter that crosses servers. */
    public boolean networked() {
        return origin_server != null && !origin_server.equals(dest_server);
    }

    public boolean moving() {
        return pending_state != null;
    }

    static String lower(String s) {
        return s == null ? null : s.toLowerCase(java.util.Locale.ROOT).trim();
    }

    @Override
    public String toString() {
        return kind + " " + id + " " + state + "@" + custody + (moving() ? " -> " + pending_state + "@" + pending_custody : "")
                + " v" + version;
    }
}
