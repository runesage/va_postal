package com.vodhanel.minecraft.va_postal.store;

import java.util.UUID;

/** A player the network knows: the server they were last on, and whether they're online there now. */
public record NetworkPlayer(UUID id, String name, String server_id, boolean online, long last_seen) {
}
