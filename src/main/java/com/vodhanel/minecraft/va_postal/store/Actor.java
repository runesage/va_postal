package com.vodhanel.minecraft.va_postal.store;

/** Who caused a transition, for the history: a player, a postman run, Central, reconciliation, an admin. */
public final class Actor {
    public final String kind;
    public final String ref;

    private Actor(String kind, String ref) {
        this.kind = kind;
        this.ref = ref;
    }

    public static Actor player(java.util.UUID id) {
        return new Actor("PLAYER", id == null ? null : id.toString());
    }

    public static Actor postman(String office) {
        return new Actor("POSTMAN", office);
    }

    public static Actor central() {
        return new Actor("CENTRAL", null);
    }

    public static Actor reconcile() {
        return new Actor("RECONCILE", null);
    }

    public static Actor admin(String name) {
        return new Actor("ADMIN", name);
    }

    public static Actor system(String what) {
        return new Actor("SYSTEM", what);
    }
}
