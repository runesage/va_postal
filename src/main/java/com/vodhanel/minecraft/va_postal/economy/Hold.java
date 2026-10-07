package com.vodhanel.minecraft.va_postal.economy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Postage held in escrow for one piece of mail, from when it is addressed until it is delivered (see
 * {@link Postage}). The money sits in Central's account and counts toward what Central owes.
 */
public final class Hold {
    public final String id;
    public final UUID payer;
    public final boolean parcel;
    /** Postage or shipping held (the out-of-town price when it was addressed). */
    public double base;
    /** COD surcharge held. */
    public double cod;
    /** Epoch second the hold was made. */
    public final long created;
    /** Office whose postman first picked the mail up; null until then. */
    public String origin;

    public Hold(String id, UUID payer, boolean parcel, double base, double cod, long created, String origin) {
        this.id = id;
        this.payer = payer;
        this.parcel = parcel;
        this.base = base;
        this.cod = cod;
        this.created = created;
        this.origin = origin;
    }

    public double total() {
        return base + cod;
    }

    Map<String, Object> to_map() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payer", payer.toString());
        m.put("parcel", parcel);
        m.put("base", base);
        m.put("cod", cod);
        m.put("created", created);
        if (origin != null) {
            m.put("origin", origin);
        }
        return m;
    }

    static Hold from_map(String id, Map<?, ?> m) {
        try {
            UUID payer = UUID.fromString(String.valueOf(m.get("payer")));
            Object origin = m.get("origin");
            return new Hold(id, payer, Boolean.parseBoolean(String.valueOf(m.get("parcel"))),
                    num(m.get("base")), num(m.get("cod")), (long) num(m.get("created")),
                    origin == null ? null : String.valueOf(origin));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : o == null ? 0.0D : Double.parseDouble(String.valueOf(o));
    }
}
