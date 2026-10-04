package com.vodhanel.minecraft.va_postal.mail;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.store.Actor;
import com.vodhanel.minecraft.va_postal.store.ConflictException;
import com.vodhanel.minecraft.va_postal.store.MailRecord;
import com.vodhanel.minecraft.va_postal.store.MailState;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Letter records (docs/design/persistent-state.md, phase P1): a letter gets a record and a
 * {@code postal:mail_id} when it's addressed; its moves are recorded around the world changes the
 * dispatcher makes. With no store open, letters simply aren't tracked and everything else works as before.
 */
public final class Letters {
    private static final Gson GSON = new Gson();

    private Letters() {
    }

    static MailStore store() {
        return MailStores.active();
    }

    /**
     * Records a newly addressed letter and returns {@code book} carrying its id. A letter that was
     * already tracked and is re-addressed before collection gets a new record; the old one is closed as
     * returned to its sender.
     */
    public static ItemStack posted(ItemStack book, UUID sender, String origin_office, String dest_office,
                                   String dest_address, UUID attention) {
        MailStore store = store();
        if (store == null || book == null) {
            return book;
        }
        try {
            UUID previous = MailIds.read(book);
            if (previous != null) {
                store.get(previous).ifPresent(r -> {
                    if (r.state == MailState.POSTED && !r.moving()) {
                        store.transition(r, MailState.RETURNED, r.custody, Actor.player(sender), "re-addressed");
                    }
                });
            }
            UUID id = UUID.randomUUID();
            store.create(MailRecord.new_letter(id, store.server_id(), origin_office, dest_office, dest_address,
                    sender, attention, payload(book), data_version(), System.currentTimeMillis()),
                    sender == null ? Actor.system("console") : Actor.player(sender),
                    "addressed to " + dest_office + ", " + dest_address);
            return MailIds.write(book, id);
        } catch (StoreException | ConflictException e) {
            Util.cinform("[Postal] Could not record a letter; it will travel untracked: " + e.getMessage());
            return book;
        }
    }

    /** The post office nearest {@code player} (the letter's origin), or null if there is none. */
    public static String nearest_office(org.bukkit.entity.Player player) {
        String[] list = com.vodhanel.minecraft.va_postal.config.C_Arrays.geo_po_list_sorted(player);
        if (list != null && list.length > 0) {
            String[] parts = list[0].split(",");
            if (parts.length > 1) {
                return parts[1].trim();
            }
        }
        return null;
    }

    public static Optional<MailRecord> record(ItemStack book) {
        MailStore store = store();
        UUID id = MailIds.read(book);
        if (store == null || id == null) {
            return Optional.empty();
        }
        try {
            return store.get(id);
        } catch (StoreException e) {
            return Optional.empty();
        }
    }

    // ---- Ledger-first moves ----------------------------------------------------------------

    /**
     * A letter move in progress (docs/design/persistent-state.md §3): {@link #begin} records the destination
     * before the world changes, {@link #commit} once the book is there, {@link #cancel} if it didn't go.
     */
    public static final class Move {
        /** Untracked book (or no store): move it the old way. */
        public static final Move UNTRACKED = new Move(Result.UNTRACKED, null);

        public enum Result {
            /** Tracked and begun: move it, then commit. */
            BEGUN,
            /** Not tracked: move it as before. */
            UNTRACKED,
            /** A copy of a letter whose record is elsewhere (or already delivered): remove it, never route it. */
            STALE,
            /** A move is already in flight for it (reconciliation will settle it): leave it this round. */
            SKIP
        }

        public final Result result;
        private MailRecord record;

        private Move(Result result, MailRecord record) {
            this.result = result;
            this.record = record;
        }

        public boolean proceed() {
            return result == Result.BEGUN || result == Result.UNTRACKED;
        }

        public void commit() {
            if (result != Result.BEGUN) {
                return;
            }
            try {
                record = store().commit_move(record, Actor.system("world"), null);
            } catch (StoreException | ConflictException e) {
                Util.cinform("[Postal] Could not commit the move of letter " + record.id + " (reconciliation will): " + e.getMessage());
            }
        }

        public void cancel(String why) {
            if (result != Result.BEGUN) {
                return;
            }
            try {
                record = store().cancel_move(record, Actor.system("world"), why);
            } catch (StoreException | ConflictException e) {
                Util.cinform("[Postal] Could not cancel the move of letter " + record.id + ": " + e.getMessage());
            }
        }
    }

    /**
     * Phase 1 of moving {@code book}, found in the chest at {@code found_at}, to {@code to} in the chest at
     * {@code to_chest}. Untracked books return {@link Move#UNTRACKED}.
     */
    public static Move begin(ItemStack book, String found_at, MailState to, String to_chest, Actor actor) {
        MailStore store = store();
        UUID id = MailIds.read(book);
        if (store == null || id == null) {
            return Move.UNTRACKED;
        }
        try {
            Optional<MailRecord> found = store.get(id);
            if (found.isEmpty()) {
                Util.cinform("[Postal] A book carries an unknown mail id " + id + "; not routing it.");
                return new Move(Move.Result.STALE, null);
            }
            MailRecord r = found.get();
            if (r.moving()) {
                return new Move(Move.Result.SKIP, r);
            }
            if (r.state.terminal() || !where_record_says(r, found_at)) {
                Util.cinform("[Postal] Removing a stale copy of letter " + r.id + " (its record: " + r.state + "@" + r.custody + ").");
                return new Move(Move.Result.STALE, r);
            }
            MailRecord begun = store.begin_move(r, to, com.vodhanel.minecraft.va_postal.store.Custody.chest(block_key(to_chest)), actor, null);
            return new Move(Move.Result.BEGUN, begun);
        } catch (StoreException | ConflictException e) {
            Util.cinform("[Postal] Letter " + id + " can't move now: " + e.getMessage());
            return new Move(Move.Result.SKIP, null);
        }
    }

    /**
     * Records that a tracked letter is now in the chest at {@code chest} in {@code state} without a move
     * (e.g. a player dropped it straight into a post office). Only applies to a letter still with its sender.
     */
    public static void arrived(ItemStack book, String chest, MailState state, Actor actor) {
        MailStore store = store();
        UUID id = MailIds.read(book);
        if (store == null || id == null) {
            return;
        }
        try {
            store.get(id).ifPresent(r -> {
                if (r.state == MailState.POSTED && !r.moving()) {
                    store.transition(r, state, com.vodhanel.minecraft.va_postal.store.Custody.chest(block_key(chest)), actor, "handed in at the post office");
                }
            });
        } catch (StoreException | ConflictException e) {
            Util.cinform("[Postal] Could not record letter " + id + " at its post office: " + e.getMessage());
        }
    }

    /** True if the record allows the book to be in the chest at {@code found_at}. */
    static boolean where_record_says(MailRecord r, String found_at) {
        if (r.custody.kind == com.vodhanel.minecraft.va_postal.store.Custody.Kind.NONE) {
            return r.state == MailState.POSTED; // still with its sender, who put it in a chest
        }
        return r.custody.kind == com.vodhanel.minecraft.va_postal.store.Custody.Kind.CHEST
                && same_place(r.custody.ref, found_at);
    }

    /** Location keys compare by block, whatever decimals they were written with. */
    static boolean same_place(String a, String b) {
        return a != null && b != null && block_key(a).equals(block_key(b));
    }

    public static String block_key(String location) {
        String[] p = location.split(",");
        if (p.length < 4) {
            return location.trim();
        }
        try {
            return p[0].trim() + "," + (int) Math.floor(Double.parseDouble(p[1].trim())) + ","
                    + (int) Math.floor(Double.parseDouble(p[2].trim())) + "," + (int) Math.floor(Double.parseDouble(p[3].trim()));
        } catch (NumberFormatException e) {
            return location.trim();
        }
    }

    /** {@code LETTER_V1}: title, author and pages as text. No item fields, by design (letters may cross servers). */
    static byte[] payload(ItemStack item) {
        Book book = new Book(item);
        JsonObject o = new JsonObject();
        o.addProperty("title", book.getTitle());
        o.addProperty("author", book.getAuthor());
        JsonArray pages = new JsonArray();
        String[] p = book.getPages();
        if (p != null) {
            for (String page : p) {
                pages.add(page);
            }
        }
        o.add("pages", pages);
        return GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    /** Rebuilds a letter's book from its record (recovery, or a letter arriving from another server). */
    public static ItemStack materialise(MailRecord record) {
        JsonObject o = GSON.fromJson(new String(record.payload, StandardCharsets.UTF_8), JsonObject.class);
        JsonArray arr = o.getAsJsonArray("pages");
        String[] pages = new String[arr.size()];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = arr.get(i).getAsString();
        }
        ItemStack item = new Book(o.get("title").getAsString(), o.get("author").getAsString(), pages).generateItemStack();
        return MailIds.write(item, record.id);
    }

    @SuppressWarnings("deprecation")
    private static int data_version() {
        try {
            return Bukkit.getUnsafe().getDataVersion();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }
}
