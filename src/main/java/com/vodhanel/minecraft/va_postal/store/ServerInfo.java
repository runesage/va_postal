package com.vodhanel.minecraft.va_postal.store;

import java.util.UUID;

/** A server using the mail database: its id, the running instance, and when it was last heard from. */
public record ServerInfo(String server_id, UUID instance, long started_at, long last_seen) {
}
