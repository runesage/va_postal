package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.store.DirectoryEntry;
import com.vodhanel.minecraft.va_postal.store.SqlMailStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rented P.O. boxes are published in the network directory, so other servers can address them. */
class DirectoryBoxesTest {
    @TempDir
    Path dir;

    @Test
    void rentedBoxesArePublishedAsAddressesOfTheirRenters() {
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + dir.resolve("postal.db"));
        SqlMailStore store = new SqlMailStore(sqlite, "beta", null);
        try {
            UUID alex = UUID.randomUUID();
            DirectoryEntry office = new DirectoryEntry("beta", "testville", null, null, true, false, null);
            assertEquals(List.of(office), Directory.with_boxes(store, List.of(office))); // no boxes rented yet

            store.rent_box("testville", alex, "Alex", 10L);
            List<DirectoryEntry> entries = Directory.with_boxes(store, List.of(office));
            assertEquals(2, entries.size());
            DirectoryEntry box = entries.get(1);
            assertEquals("beta", box.server_id());
            assertEquals("testville", box.office());
            assertEquals("box1", box.address());
            assertEquals(alex, box.owner());
            assertTrue(box.open());

            store.release_box("testville", 1);
            assertEquals(List.of(office), Directory.with_boxes(store, List.of(office)));
        } finally {
            store.close();
        }
    }
}
