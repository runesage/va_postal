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
import com.vodhanel.minecraft.va_postal.store.StoreQueue;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
        return posted(book, sender, origin_office, null, dest_office, dest_address, attention);
    }

    /**
     * {@code dest_server}: the server the destination office is on (null: this one). A letter for another server
     * must be tracked to travel; the caller checks the returned book carries a mail id.
     */
    public static ItemStack posted(ItemStack book, UUID sender, String origin_office, String dest_server, String dest_office,
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
            store.create(MailRecord.new_letter(id, store.server_id(), dest_server == null ? store.server_id() : dest_server,
                    origin_office, dest_office, dest_address,
                    sender, attention, payload(book), data_version(), System.currentTimeMillis(), HoldTag.read(book)),
                    sender == null ? Actor.system("console") : Actor.player(sender),
                    "addressed to " + (dest_server == null ? "" : dest_server + ":") + dest_office + ", " + dest_address);
            return MailIds.write(book, id);
        } catch (StoreException | ConflictException e) {
            Util.cinform("[Postal] Could not record a letter; it will travel untracked: " + e.getMessage());
            return book;
        }
    }

    /**
     * {@code /att} rewrote the addressed {@code book}'s attention line: the book's record, if it is still
     * POSTED (not collected or moving), gets the same attention player and book text, so the two agree.
     */
    public static void attention_changed(ItemStack book, UUID sender, UUID attention) {
        MailStore store = store();
        UUID id = MailIds.read(book);
        if (store == null || id == null) {
            return;
        }
        try {
            store.get(id).ifPresent(r -> {
                if (r.state == MailState.POSTED && !r.moving()) {
                    store.set_attention(r, attention, r.kind == com.vodhanel.minecraft.va_postal.store.MailKind.LETTER ? payload(book) : null,
                            sender == null ? Actor.system("console") : Actor.player(sender), "attention changed");
                }
            });
        } catch (StoreException | ConflictException e) {
            Util.cinform("[Postal] Could not update a letter's record after /att: " + e.getMessage());
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

        /** The book is in place: record it, in the background (nothing waits on it). */
        public void commit() {
            if (result != Result.BEGUN) {
                return;
            }
            MailRecord begun = record;
            StoreQueue.later("Committing the move of letter " + begun.id + " (reconciliation will)",
                    () -> store().commit_move(begun, Actor.system("world"), null));
        }

        /** The book didn't go: record it, in the background. */
        public void cancel(String why) {
            if (result != Result.BEGUN) {
                return;
            }
            MailRecord begun = record;
            StoreQueue.later("Cancelling the move of letter " + begun.id,
                    () -> store().cancel_move(begun, Actor.system("world"), why));
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
                // Not ours to destroy (e.g. the mail store was reset but the world wasn't): leave it where it is.
                Util.cinform("[Postal] A book carries an unknown mail id " + id + "; leaving it where it is, not routing it.");
                return new Move(Move.Result.SKIP, null);
            }
            MailRecord r = found.get();
            if (r.moving()) {
                return new Move(Move.Result.SKIP, r);
            }
            if (r.state.terminal()) {
                return new Move(Move.Result.SKIP, r); // e.g. a delivered letter a player put back: theirs, not ours to route
            }
            if (!where_record_says(r, found_at)) {
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
        UUID id = MailIds.read(book);
        if (store() == null || id == null) {
            return;
        }
        StoreQueue.later("Recording letter " + id + " at its post office", () -> {
            MailStore store = store();
            if (store == null) {
                return;
            }
            store.get(id).ifPresent(r -> {
                if (r.state == MailState.POSTED && !r.moving()) {
                    store.transition(r, state, com.vodhanel.minecraft.va_postal.store.Custody.chest(block_key(chest)), actor, "handed in at the post office");
                }
            });
        });
    }

    // ---- Route runs ------------------------------------------------------------------------

    private static final java.util.Map<Integer, String[]> runs = new java.util.HashMap<>();

    /**
     * A postman sets out for {@code address}: records the run (so a crash can return its letters) and marks the
     * letters waiting at the office for that address as out for delivery.
     */
    public static synchronized void route_started(int npc, String office, String address, String office_chest) {
        if (store() == null || office == null || address == null) {
            return;
        }
        if (runs.containsKey(npc)) {
            // The last run never ended: settle it first, now, so its letters can go out on this one.
            close_run(runs.remove(npc), true);
        }
        String run = UUID.randomUUID().toString();
        runs.put(npc, new String[]{run, office, address});
        long now = System.currentTimeMillis();
        // In the background: the postman's pickups from the office chest queue behind it, so they see these
        // letters out for delivery.
        StoreQueue.later("Recording the route run to " + office + ", " + address, () -> {
            MailStore store = store();
            if (store == null) {
                return;
            }
            store.start_run(run, office, address, Integer.toString(npc), now);
            for (MailState waiting : new MailState[]{MailState.AT_DEST_BRANCH, MailState.AT_ORIGIN_BRANCH}) {
                for (MailRecord r : store.by_destination(waiting, office)) {
                    // Only letters in this office's chest: a cross-town letter still at its origin office is also
                    // AT_ORIGIN_BRANCH with this destination.
                    if (address.equalsIgnoreCase(r.dest_address) && !r.moving() && r.custody.kind
                            == com.vodhanel.minecraft.va_postal.store.Custody.Kind.CHEST && same_place(r.custody.ref, office_chest)) {
                        store.transition(r, MailState.OUT_FOR_DELIVERY, r.custody, Actor.postman(office), "run " + run);
                    }
                }
            }
        });
    }

    /** The run is over: anything still out for delivery (e.g. a full mailbox) goes back to waiting at the office. */
    public static synchronized void route_finished(int npc) {
        String[] run = runs.remove(npc);
        if (store() == null || run == null) {
            return;
        }
        close_run(run, false);
    }

    /**
     * Closes {@code run}: its letters still out for delivery go back to waiting at the office, or MISSING if they're
     * no longer in the office chest (where a book waits while it's out for delivery). {@code now}: all of it here,
     * on the main thread; otherwise the store work runs in the background and only the look in the chest is here.
     */
    private static void close_run(String[] run, boolean now) {
        if (now) {
            try {
                List<MailRecord> out = still_out(run);
                settle(run, out, taken(out));
            } catch (StoreException | ConflictException e) {
                Util.cinform("[Postal] Could not close the route run " + run[0] + ": " + e.getMessage());
            }
            return;
        }
        StoreQueue.later("Closing the route run " + run[0], () -> {
            List<MailRecord> out = still_out(run);
            if (out.isEmpty()) {
                settle(run, out, List.of());
                return;
            }
            // Whether each book is still in the office chest is a question for the world, on the main thread.
            org.bukkit.Bukkit.getScheduler().runTask(com.vodhanel.minecraft.va_postal.VA_postal.plugin, () -> {
                List<Boolean> taken = taken(out);
                StoreQueue.later("Closing the route run " + run[0], () -> settle(run, out, taken));
            });
        });
    }

    private static List<MailRecord> still_out(String[] run) {
        return store().by_destination(MailState.OUT_FOR_DELIVERY, run[1]).stream()
                .filter(r -> run[2].equalsIgnoreCase(r.dest_address) && !r.moving())
                .toList();
    }

    /** For each letter: true if it's no longer in the office chest. Main thread. */
    private static List<Boolean> taken(List<MailRecord> out) {
        List<Boolean> taken = new java.util.ArrayList<>();
        for (MailRecord r : out) {
            org.bukkit.inventory.Inventory chest = Reconciler.chest(r.custody, false);
            taken.add(chest != null && !Reconciler.holds(chest, r.id));
        }
        return taken;
    }

    private static void settle(String[] run, List<MailRecord> out, List<Boolean> taken) {
        MailStore store = store();
        for (int i = 0; i < out.size(); i++) {
            MailRecord r = out.get(i);
            try {
                if (taken.get(i)) {
                    MailRecord gone = store.transition(r, MailState.MISSING, r.custody, Actor.postman(run[1]),
                            "not in the office chest when its route ended");
                    announce_missing(gone);
                } else {
                    store.transition(r, MailState.AT_DEST_BRANCH, r.custody, Actor.postman(run[1]), "run ended without delivering it");
                }
            } catch (ConflictException moved) {
                // It moved on since we looked (a postman picked it up): its new state stands.
            }
        }
        store.end_run(run[0]);
    }

    /** MailMissingEvent is a Bukkit event: fired on the main thread. */
    private static void announce_missing(MailRecord r) {
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            Reconciler.missing(r, "not in the office chest when its route ended");
        } else {
            org.bukkit.Bukkit.getScheduler().runTask(com.vodhanel.minecraft.va_postal.VA_postal.plugin,
                    () -> Reconciler.missing(r, "not in the office chest when its route ended"));
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

    /**
     * Rebuilds a piece of mail's book from its record (recovery, or a letter arriving from another server): a
     * letter, or a parcel's shipping label. It carries its mail id and its postage hold.
     */
    public static ItemStack materialise(MailRecord record) {
        ItemStack item = record.kind == com.vodhanel.minecraft.va_postal.store.MailKind.PARCEL
                ? Parcels.label(record) : letter_book(record);
        // A letter from another server carries no hold here: its postage was settled where it was posted.
        MailStore store = store();
        boolean arrived = record.networked() && store != null && store.server_id().equals(record.dest_server);
        return HoldTag.write(MailIds.write(item, record.id), arrived ? null : record.hold_id);
    }

    /** The postage hold recorded for this book's mail, or null (for books whose hold tag was lost). */
    public static String hold_of(ItemStack book) {
        MailStore store = store();
        UUID id = MailIds.read(book);
        if (store == null || id == null) {
            return null;
        }
        try {
            return store.get(id).map(r -> r.hold_id).orElse(null);
        } catch (StoreException e) {
            return null;
        }
    }

    private static ItemStack letter_book(MailRecord record) {
        JsonObject o = GSON.fromJson(new String(record.payload, StandardCharsets.UTF_8), JsonObject.class);
        JsonArray arr = o.getAsJsonArray("pages");
        String[] pages = new String[arr.size()];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = arr.get(i).getAsString();
        }
        String title = o.get("title").getAsString();
        MailStore store = store();
        if (record.networked() && store != null && store.server_id().equals(record.dest_server)) {
            // Its title routes it: "server:office" on the way out, the office's own name once it has arrived.
            title = Util.df(record.dest_office);
        }
        return new Book(title, o.get("author").getAsString(), pages).generateItemStack();
    }

    @SuppressWarnings("deprecation")
    static int data_version() {
        try {
            return Bukkit.getUnsafe().getDataVersion();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }
}
