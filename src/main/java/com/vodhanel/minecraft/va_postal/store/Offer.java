package com.vodhanel.minecraft.va_postal.store;

import java.util.UUID;

/**
 * An offer to sell a post office to another player through Central (docs/design/central-office.md §3): made by the
 * office's owner, accepted (or declined) by the buyer at Central's counter. Nothing is paid until it's accepted.
 */
public record Offer(UUID id, String server_id, String office, UUID seller, String seller_name, UUID buyer, String buyer_name,
                    double price, State state, long created_at, long expires_at, long updated_at) {

    public enum State {
        /** Waiting for the buyer. At most one per office. */
        OPEN,
        /** The buyer paid; the office is theirs. */
        ACCEPTED,
        /** The buyer said no. */
        DECLINED,
        /** The seller took it back (or no longer owned the office). */
        WITHDRAWN,
        /** Not accepted in time. */
        EXPIRED
    }

    public Offer with_state(State to, long now) {
        return new Offer(id, server_id, office, seller, seller_name, buyer, buyer_name, price, to, created_at, expires_at, now);
    }
}
