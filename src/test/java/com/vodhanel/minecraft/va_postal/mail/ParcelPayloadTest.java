package com.vodhanel.minecraft.va_postal.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParcelPayloadTest {
    @Test
    void roundTripsLabelChestAndItems() {
        byte[] sword = {10, 0, -1, 42, 7};
        byte[] logs = {1, 2, 3};
        ParcelPayload p = new ParcelPayload("Testville", "Home", List.of("§7To:\n§c Testville\n", "page two"),
                "world,40,-60,0,SOUTH", List.of(0, 13), List.of(sword, logs));
        ParcelPayload back = ParcelPayload.decode(p.encode());
        assertEquals("Testville", back.title);
        assertEquals("Home", back.author);
        assertEquals(p.pages, back.pages);
        assertEquals("world,40,-60,0,SOUTH", back.chest);
        assertEquals(List.of(0, 13), back.slots);
        assertArrayEquals(sword, back.items.get(0));
        assertArrayEquals(logs, back.items.get(1));
    }

    @Test
    void aParcelWithNoChestStillDecodes() {
        ParcelPayload p = new ParcelPayload("T", "A", List.of(), null, List.of(), List.of());
        assertNull(ParcelPayload.decode(p.encode()).chest);
    }

    @Test
    void everyItemNeedsItsSlot() {
        assertThrows(IllegalArgumentException.class,
                () -> new ParcelPayload("T", "A", List.of(), null, List.of(0), List.of()));
    }
}
