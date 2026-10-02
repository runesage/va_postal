package com.vodhanel.minecraft.va_postal.economy;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Identity of the economy accounts owned by the postal service itself.
 * <p>
 * Central and every local post office get an ordinary economy account under a made-up UUID (the
 * Towny approach) instead of a Vault "bank". The UUIDs are derived from the office name, so they are
 * stable across restarts and servers without being stored anywhere, and they carry version 2, which
 * EssentialsX treats as an NPC account and no real (v4) or offline-mode (v3) player can ever have.
 */
public final class OfficeAccounts {
    /** Central lives in its own namespace so a local office named "Central" can't share its account. */
    private static final String CENTRAL_NAMESPACE = "va_postal:central";
    private static final String NAMESPACE = "va_postal:office:";
    private static final String CENTRAL_NAME = "postal-central";
    private static final String NAME_PREFIX = "postal-po-";
    /** Name-based economies commonly cap account names; keep office names well inside that. */
    private static final int MAX_NAME_LENGTH = 32;

    private OfficeAccounts() {
    }

    public static UUID central_id() {
        return npc_id(CENTRAL_NAMESPACE);
    }

    public static String central_name() {
        return CENTRAL_NAME;
    }

    /** Stable account UUID for a local post office (case-insensitive on the office name). */
    public static UUID office_id(String office) {
        return npc_id(NAMESPACE + key(office));
    }

    /** Name-based (v3) UUID with the version nibble rewritten to 2. */
    private static UUID npc_id(String seed) {
        UUID v3 = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        long msb = (v3.getMostSignificantBits() & ~0xF000L) | 0x2000L;
        return new UUID(msb, v3.getLeastSignificantBits());
    }

    /** Human-readable account name, e.g. {@code postal-po-riverside}. */
    public static String office_name(String office) {
        String name = NAME_PREFIX + key(office).replaceAll("[^a-z0-9_-]", "_");
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    public static boolean is_office_account(UUID id) {
        return id != null && id.version() == 2;
    }

    static String key(String office) {
        if (office == null) {
            throw new IllegalArgumentException("office name is null");
        }
        String key = office.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("office name is blank");
        }
        return key;
    }
}
