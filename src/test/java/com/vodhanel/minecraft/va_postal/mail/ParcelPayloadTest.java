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

    /** A compound {count:int 3, id:"minecraft:oak_log"}, like an item's NBT. */
    private static byte[] nbt(String id) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(b);
        d.writeByte(10);
        d.writeUTF("");
        d.writeByte(3);
        d.writeUTF("count");
        d.writeInt(3);
        d.writeByte(8);
        d.writeUTF("id");
        d.writeUTF(id);
        d.writeByte(0);
        return b.toByteArray();
    }

    private static byte[] gzip(byte[] b) throws java.io.IOException {
        java.io.ByteArrayOutputStream z = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(z)) {
            gz.write(b);
        }
        return z.toByteArray();
    }

    @Test
    void readsAnItemsIdFromItsBytes() throws Exception {
        assertEquals("minecraft:oak_log", ParcelPayload.item_id(nbt("minecraft:oak_log")));
        assertEquals("minecraft:oak_log", ParcelPayload.item_id(gzip(nbt("minecraft:oak_log"))));
        assertNull(ParcelPayload.item_id(new byte[]{1, 2, 3}));
    }

    @Test
    void replacesAnItemsId() throws Exception {
        byte[] retired = ParcelPayload.with_item_id(gzip(nbt("minecraft:oak_log")), "minecraft:postal_retired_item");
        assertEquals("minecraft:postal_retired_item", ParcelPayload.item_id(retired));
        assertArrayEquals(nbt("minecraft:postal_retired_item"), new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(retired)).readAllBytes());
    }
}
