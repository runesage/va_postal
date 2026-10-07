package com.vodhanel.minecraft.va_postal.store;

import java.util.UUID;

/** One row of the append-only {@code mail_event} history. */
public final class MailEvent {
    public final UUID mail_id;
    public final int version;
    public final long at;
    public final String server_id;
    public final MailState from_state;
    public final MailState to_state;
    public final String actor_kind;
    public final String actor_ref;
    public final String detail;

    public MailEvent(UUID mail_id, int version, long at, String server_id, MailState from_state, MailState to_state,
                     String actor_kind, String actor_ref, String detail) {
        this.mail_id = mail_id;
        this.version = version;
        this.at = at;
        this.server_id = server_id;
        this.from_state = from_state;
        this.to_state = to_state;
        this.actor_kind = actor_kind;
        this.actor_ref = actor_ref;
        this.detail = detail;
    }
}
