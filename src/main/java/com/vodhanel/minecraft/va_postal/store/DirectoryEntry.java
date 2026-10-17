package com.vodhanel.minecraft.va_postal.store;

import java.util.UUID;

/**
 * A row of the network-wide address book (docs/design/persistent-state.md §5): a post office
 * ({@code address == null}) or an address, as a server published it.
 *
 * @param location where it is on that server ({@code world,x,y,z}); never used across servers
 */
public record DirectoryEntry(String server_id, String office, String address, UUID owner, boolean open,
                             boolean central, String location) {
    public boolean is_office() {
        return address == null;
    }
}
