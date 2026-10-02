package com.vodhanel.minecraft.va_postal.economy;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OfficeAccountsTest {

    @Test
    void office_ids_are_version_2_so_essentials_treats_them_as_npcs() {
        assertEquals(2, OfficeAccounts.central_id().version());
        assertEquals(2, OfficeAccounts.office_id("Riverside").version());
        assertEquals(2, OfficeAccounts.office_id("Riverside").variant());
        assertTrue(OfficeAccounts.is_office_account(OfficeAccounts.office_id("Riverside")));
        assertFalse(OfficeAccounts.is_office_account(UUID.randomUUID()));
    }

    @Test
    void office_ids_are_stable_and_case_insensitive() {
        assertEquals(OfficeAccounts.office_id("Riverside"), OfficeAccounts.office_id(" riverside "));
        assertNotEquals(OfficeAccounts.office_id("Riverside"), OfficeAccounts.office_id("Hilltop"));
        assertNotEquals(OfficeAccounts.central_id(), OfficeAccounts.office_id("Riverside"));
        assertNotEquals(OfficeAccounts.central_id(), OfficeAccounts.office_id("Central"));
        assertNotEquals(OfficeAccounts.central_name(), OfficeAccounts.office_name("Central"));
        // Pinned so an accidental change to the derivation (which would orphan every office balance) fails loudly.
        assertEquals(UUID.fromString("2ca1fae9-c175-239e-9eb2-df89430f098b"), OfficeAccounts.central_id());
        assertEquals(UUID.fromString("e8ef95ea-d36d-2804-9638-469f1fba2601"), OfficeAccounts.office_id("Riverside"));
    }

    @Test
    void office_names_are_sanitised_and_bounded() {
        assertEquals("postal-central", OfficeAccounts.central_name());
        assertEquals("postal-po-new_haven", OfficeAccounts.office_name("New Haven"));
        assertTrue(OfficeAccounts.office_name("a-really-long-post-office-name-that-goes-on").length() <= 32);
        assertThrows(IllegalArgumentException.class, () -> OfficeAccounts.office_id(" "));
    }

    @Test
    void npc_holder_carries_identity_and_defaults_everything_else() {
        UUID id = OfficeAccounts.office_id("Riverside");
        OfflinePlayer holder = NpcAccountHolder.of(id, "postal-riverside");
        assertEquals(id, holder.getUniqueId());
        assertEquals("postal-riverside", holder.getName());
        assertFalse(holder.isOnline());
        assertNull(holder.getPlayer());
        assertEquals(holder, NpcAccountHolder.of(id, "other-name"));
        assertEquals(id.hashCode(), holder.hashCode());
    }
}
