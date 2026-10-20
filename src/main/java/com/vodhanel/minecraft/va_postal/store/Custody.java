package com.vodhanel.minecraft.va_postal.store;

import java.util.Objects;

/**
 * Where a piece of mail physically is: a chest (by location key {@code world,x,y,z}), a postman's run, a P.O. box
 * ({@code office#number}: kept in the store, not in any chest; docs/design/po-boxes.md), or nowhere Postal holds it
 * yet (with its sender, or not materialised on this server).
 */
public final class Custody {
    public enum Kind { CHEST, ROUTE, BOX, NONE }

    public static final Custody NONE = new Custody(Kind.NONE, null);

    public final Kind kind;
    public final String ref;

    private Custody(Kind kind, String ref) {
        this.kind = kind;
        this.ref = ref;
    }

    public static Custody chest(String location_key) {
        return new Custody(Kind.CHEST, Objects.requireNonNull(location_key));
    }

    public static Custody route(String run_id) {
        return new Custody(Kind.ROUTE, Objects.requireNonNull(run_id));
    }

    /** P.O. box {@code number} at {@code office}. */
    public static Custody box(String office, int number) {
        return new Custody(Kind.BOX, office.toLowerCase(java.util.Locale.ROOT).trim() + "#" + number);
    }

    static Custody of(String kind, String ref) {
        if (kind == null) {
            return NONE;
        }
        Kind k = Kind.valueOf(kind);
        return k == Kind.NONE ? NONE : new Custody(k, ref);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Custody && ((Custody) o).kind == kind && Objects.equals(((Custody) o).ref, ref);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, ref);
    }

    @Override
    public String toString() {
        return kind == Kind.NONE ? "NONE" : kind + "@" + ref;
    }
}
