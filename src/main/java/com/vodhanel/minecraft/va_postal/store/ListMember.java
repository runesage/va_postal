package com.vodhanel.minecraft.va_postal.store;

import java.util.UUID;

/**
 * A player's place on a mailing list (schema 10): an office's subscribers ({@code office:<office>}, mode SUBSCRIBED)
 * or a Towny town's residents ({@code town:<town>}, where everyone is on it unless the row says OPTED_OUT).
 */
public record ListMember(String server_id, String list, UUID player, String player_name, String mode, long at) {
    public static final String SUBSCRIBED = "SUBSCRIBED";
    public static final String OPTED_OUT = "OPTED_OUT";
}
