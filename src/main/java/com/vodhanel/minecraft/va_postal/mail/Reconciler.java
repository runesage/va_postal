package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.common.AnsiColor;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.store.Actor;
import com.vodhanel.minecraft.va_postal.store.ConflictException;
import com.vodhanel.minecraft.va_postal.store.Custody;
import com.vodhanel.minecraft.va_postal.store.MailKind;
import com.vodhanel.minecraft.va_postal.store.MailRecord;
import com.vodhanel.minecraft.va_postal.store.MailState;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Makes the world agree with the mail store (docs/design/persistent-state.md §7). Runs at startup (loading
 * the chunks it needs) and periodically (only for chunks already loaded):
 * <ol>
 *   <li>a move in flight is committed if the book reached its destination, cancelled if it's still at the
 *       source, or re-materialised at the destination from the record if it's in neither;</li>
 *   <li>a letter whose chest no longer holds it is marked {@code MISSING} (griefed);</li>
 *   <li>in the chests it looks at, a tracked copy whose record is elsewhere is a stale duplicate and is
 *       removed; a postal-looking book with no record is flagged as forged and left alone;</li>
 *   <li>route runs left by a crash return their letters from {@code OUT_FOR_DELIVERY} to the office.</li>
 * </ol>
 */
public final class Reconciler {
    /** How far back to check deliveries against a world rolled back by a crash (Paper autosaves every 5 minutes). */
    static final long RECENT_DELIVERY_MILLIS = 60L * 60L * 1000L;

    private Reconciler() {
    }

    /** Counts of what a pass did, for the log. */
    public static final class Report {
        public int checked, committed, cancelled, rematerialised, rolled_forward, missing, stale_removed, forged, runs_returned;

        @Override
        public String toString() {
            return checked + " letters checked: " + committed + " moves committed, " + cancelled + " cancelled, "
                    + rematerialised + " rebuilt, " + rolled_forward + " moved forward after a world rollback, " + missing + " missing, " + stale_removed + " stale copies removed, "
                    + forged + " forged books flagged, " + runs_returned + " interrupted runs returned";
        }

        boolean anything() {
            return committed + cancelled + rematerialised + rolled_forward + missing + stale_removed + forged + runs_returned > 0;
        }
    }

    /** @param startup true: also settle route runs and load chunks as needed */
    public static Report run(boolean startup) {
        Report report = new Report();
        MailStore store = MailStores.active();
        if (store == null) {
            return report;
        }
        try {
            if (startup) {
                return_interrupted_runs(store, report);
            }
            Set<String> inspected = new HashSet<>();
            for (MailRecord r : store.held_here()) {
                report.checked++;
                settle(store, r, startup, report);
                if (r.custody.kind == Custody.Kind.CHEST) {
                    inspected.add(r.custody.ref);
                }
                if (r.pending_custody != null && r.pending_custody.kind == Custody.Kind.CHEST) {
                    inspected.add(r.pending_custody.ref);
                }
            }
            // Recently delivered letters: a crash can roll the world back past the delivery (the world saves
            // every few minutes, the store commits at once), leaving the letter in the office chest.
            for (MailRecord r : store.delivered_since(System.currentTimeMillis() - RECENT_DELIVERY_MILLIS)) {
                if (r.kind == MailKind.LETTER && r.custody.kind == Custody.Kind.CHEST) {
                    Inventory at = chest(r.custody, startup);
                    if (at != null && !holds(at, r.id)) {
                        roll_forward(store, r, at, startup, report);
                    }
                }
            }
            for (String chest : inspected) {
                sweep_chest(store, chest, startup, report);
            }
        } catch (StoreException e) {
            Util.cinform(AnsiColor.RED + "[Postal] Reconciliation stopped: " + e.getMessage());
        }
        if (startup || report.anything()) {
            Util.cinform("[Postal] Reconciliation: " + report);
        }
        return report;
    }

    /** A move younger than this may still be under way (the book is added a few ticks after it's recorded). */
    static final long IN_FLIGHT_MILLIS = 30_000L;

    private static void settle(MailStore store, MailRecord r, boolean load, Report report) {
        try {
            if (r.moving()) {
                if (!load && System.currentTimeMillis() - r.updated_at < IN_FLIGHT_MILLIS) {
                    return; // the dispatcher is mid-move; leave it to finish (at startup nothing is mid-move)
                }
                Inventory to = chest(r.pending_custody, load);
                Inventory from = r.custody.kind == Custody.Kind.CHEST ? chest(r.custody, load) : null;
                if (to == null || (r.custody.kind == Custody.Kind.CHEST && from == null)) {
                    return; // can't see both ends now (unloaded chunk): next pass
                }
                if (holds(to, r.id)) {
                    if (from != null) {
                        remove(from, r.id); // a crash after the add but before the removal
                    }
                    store.commit_move(r, Actor.reconcile(), "book found at the destination");
                    report.committed++;
                } else if (from != null && holds(from, r.id)) {
                    store.cancel_move(r, Actor.reconcile(), "book still at the source");
                    report.cancelled++;
                } else if (to.addItem(Letters.materialise(r)).isEmpty()) {
                    store.commit_move(r, Actor.reconcile(), "rebuilt at the destination from the record");
                    report.rematerialised++;
                }
                return;
            }
            if (r.state == MailState.MISSING) {
                return; // already reported; if the book turns up again the dispatcher routes it from there
            }
            if (r.custody.kind == Custody.Kind.CHEST) {
                Inventory at = chest(r.custody, load);
                if (at == null) {
                    if (load && chest_gone(r.custody)) {
                        store.transition(r, MailState.MISSING, r.custody, Actor.reconcile(), "its chest is gone");
                        report.missing++;
                        warn_missing(r, "its chest is gone");
                    }
                    return;
                }
                if (!holds(at, r.id) && !roll_forward(store, r, at, load, report)) {
                    store.transition(r, MailState.MISSING, r.custody, Actor.reconcile(), "not in its chest");
                    report.missing++;
                    warn_missing(r, "not in its chest");
                }
            }
        } catch (ConflictException e) {
            // Changed under us (the dispatcher moved it meanwhile): fine, next pass sees the new state.
        }
    }

    /**
     * If the letter is still in the chest it was in before its current one, the world was rolled back past the
     * move: move that copy forward. Only ever moves an existing book, so it can't duplicate one (a delivered
     * letter its owner took out is simply absent from both chests, and left alone).
     */
    private static boolean roll_forward(MailStore store, MailRecord r, Inventory at, boolean load, Report report) {
        Custody before = store.previous_chest(r);
        Inventory prev = before == null ? null : chest(before, load);
        if (prev == null) {
            return false;
        }
        for (int slot = 0; slot < prev.getSize(); slot++) {
            ItemStack item = prev.getItem(slot);
            if (item != null && r.id.equals(MailIds.read(item)) && at.addItem(item).isEmpty()) {
                prev.setItem(slot, null);
                report.rolled_forward++;
                Util.cinform("[Postal] The world was behind the mail store: moved letter " + r.id + " from " + before.ref
                        + " forward to " + r.custody.ref + ".");
                return true;
            }
        }
        return false;
    }

    /** In a chest Postal holds mail in: remove stale copies of letters held elsewhere, flag forged books. */
    private static void sweep_chest(MailStore store, String key, boolean load, Report report) {
        Inventory inv = chest(Custody.chest(key), load);
        if (inv == null) {
            return;
        }
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() != Material.WRITTEN_BOOK) {
                continue;
            }
            UUID id = MailIds.read(item);
            if (id == null) {
                if (looks_like_mail(item)) {
                    report.forged++;
                    Util.cinform("[Postal] An untracked postal-looking book is in the chest at " + key
                            + "; it has no mail id, so it isn't tracked (it is routed the old way).");
                }
                continue;
            }
            Optional<MailRecord> found = store.get(id);
            if (found.isEmpty()) {
                report.forged++;
                Util.cinform("[Postal] A book in the chest at " + key + " carries an unknown mail id " + id + "; flagged, not routed.");
                continue;
            }
            MailRecord r = found.get();
            boolean belongs = Letters.where_record_says(r, key)
                    || (r.moving() && r.pending_custody != null && Letters.same_place(r.pending_custody.ref, key));
            if (!r.state.terminal() && !belongs) {
                inv.setItem(slot, null);
                report.stale_removed++;
                Util.cinform("[Postal] Removed a stale copy of letter " + id + " from " + key + " (its record: " + r.state + "@" + r.custody + ").");
            }
        }
    }

    /** Letters left OUT_FOR_DELIVERY by a run that didn't finish go back to their office. */
    private static void return_interrupted_runs(MailStore store, Report report) {
        for (String[] run : store.runs()) {
            for (MailRecord r : store.by_destination(MailState.OUT_FOR_DELIVERY, run[1])) {
                if (run[2].equalsIgnoreCase(r.dest_address) && !r.moving()) {
                    try {
                        store.transition(r, MailState.AT_DEST_BRANCH, r.custody, Actor.reconcile(), "route run " + run[0] + " interrupted");
                        report.runs_returned++;
                    } catch (ConflictException ignored) {
                    }
                }
            }
            store.end_run(run[0]);
        }
    }

    // ---- World access ----------------------------------------------------------------------

    /** The chest's inventory, or null if it can't be seen now (unloaded and {@code !load}, or not a chest). */
    static Inventory chest(Custody custody, boolean load) {
        Location loc = location(custody);
        if (loc == null) {
            return null;
        }
        if (!load && !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return null;
        }
        return loc.getBlock().getState() instanceof Chest ? ((Chest) loc.getBlock().getState()).getInventory() : null;
    }

    /** True if the chunk is loadable and there's simply no chest there any more. */
    private static boolean chest_gone(Custody custody) {
        Location loc = location(custody);
        return loc != null && !(loc.getBlock().getState() instanceof Chest);
    }

    private static Location location(Custody custody) {
        if (custody == null || custody.kind != Custody.Kind.CHEST || custody.ref == null) {
            return null;
        }
        String[] p = custody.ref.split(",");
        if (p.length < 4) {
            return null;
        }
        World w = Bukkit.getWorld(p[0]);
        if (w == null) {
            return null;
        }
        try {
            return new Location(w, Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean holds(Inventory inv, UUID id) {
        for (ItemStack item : inv.getContents()) {
            if (item != null && id.equals(MailIds.read(item))) {
                return true;
            }
        }
        return false;
    }

    private static void remove(Inventory inv, UUID id) {
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item != null && id.equals(MailIds.read(item))) {
                inv.setItem(slot, null);
            }
        }
    }

    /** A book written to look like Postal mail (letters and shipping labels both start "To: ... Mailed from:"). */
    private static boolean looks_like_mail(ItemStack item) {
        Book book = new Book(item);
        String[] pages = book.getPages();
        return book.is_valid() && pages != null && pages.length > 0 && pages[0].contains("Mailed from:");
    }

    private static void warn_missing(MailRecord r, String why) {
        missing(r, why);
    }

    /** Reports a record just marked MISSING: the log, and {@link MailMissingEvent} for claim handlers. */
    static void missing(MailRecord r, String why) {
        Util.cinform(AnsiColor.RED + "[Postal] " + (r.kind == MailKind.PARCEL ? "Parcel " : "Letter ") + r.id + " for "
                + r.dest_office + ", " + r.dest_address + " is MISSING (" + why + " at " + r.custody + ").");
        try {
            Bukkit.getPluginManager().callEvent(new com.vodhanel.minecraft.va_postal.api.MailMissingEvent(r.id, r.kind.name(),
                    r.sender, r.dest_office, r.dest_address, r.custody.toString(), why, r.cod_amount));
        } catch (RuntimeException e) {
            Util.cinform("[Postal] A MailMissingEvent listener failed: " + e);
        }
    }
}
