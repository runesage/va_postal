package com.vodhanel.minecraft.va_postal.store;

/** Where a piece of mail is in its life (docs/design/persistent-state.md §6). */
public enum MailState {
    /** Addressed by its sender, not yet collected. */
    POSTED,
    AT_ORIGIN_BRANCH,
    AT_CENTRAL,
    /** Letters only: waiting for another server to claim it (phase P4). */
    IN_NETWORK,
    AT_DEST_BRANCH,
    OUT_FOR_DELIVERY,
    DELIVERED,
    RETURNED,
    /** A parcel its recipient refused: its items went back to the sender. */
    REFUSED,
    /** A parcel its recipient accepted: its items were handed over. */
    ACCEPTED,
    EXPIRED,
    /** The record says a chest holds it, and the chest doesn't: griefed. */
    MISSING,
    CLAIMED,
    RECOVERED;

    /** True once Postal no longer moves it. */
    public boolean terminal() {
        return this == DELIVERED || this == RETURNED || this == REFUSED || this == ACCEPTED || this == EXPIRED
                || this == CLAIMED || this == RECOVERED;
    }
}
