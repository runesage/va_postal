package com.vodhanel.minecraft.va_postal.store;

import java.util.UUID;

/**
 * A deed (docs/design/deeds.md): a document bought in person, bound to its holder, and used on a chest to create
 * an address (or, as a charter, a post office). The item a player carries only names the deed and its issue; this
 * record is what counts.
 *
 * @param office  the office an address deed belongs to (a charter's: the office it will found)
 * @param name    what the deed creates, as stored (lower case, underscores)
 * @param price   paid, and held in Central's account until the deed is used, refunded or expires
 * @param issue   the issue of the item that's valid; a reissue bumps it, so an older copy stops working
 */
public record Deed(UUID id, Kind kind, State state, UUID holder, String holder_name, String server_id, String office,
                   String name, double price, int issue, long created_at, long expires_at, long updated_at) {

    public enum Kind { ADDRESS, CHARTER }

    public enum State {
        /** Bought and waiting for approval: the name is reserved and the price held. */
        PENDING,
        /** The holder has the document: the name is reserved and the price held. */
        ISSUED,
        /** Used on a chest: the address (or office) exists, and the price has gone to its accounts. */
        USED,
        /** Not used in time: refunded less the expiry fee, and the name released. */
        EXPIRED,
        /** Approval refused: refunded in full, and the name released. */
        DENIED;

        /** Holds its name and its price. */
        public boolean open() {
            return this == PENDING || this == ISSUED;
        }
    }

    public Deed with_state(State to, long now) {
        return new Deed(id, kind, to, holder, holder_name, server_id, office, name, price, issue, created_at, expires_at, now);
    }
}
