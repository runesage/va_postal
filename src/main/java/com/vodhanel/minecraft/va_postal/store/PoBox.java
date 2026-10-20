package com.vodhanel.minecraft.va_postal.store;

import java.util.UUID;

/**
 * A P.O. box lease (docs/design/po-boxes.md): box {@code number} at {@code office}, rented by {@code renter} since
 * {@code rented_at}. {@code arrears} is rent owed and not yet paid; {@code arrears_days} how many Postal days it's been
 * owed.
 */
public record PoBox(String server_id, String office, int number, UUID renter, String renter_name, long rented_at,
                    double arrears, int arrears_days) {

    /** "Box 3". */
    public String label() {
        return "Box " + number;
    }
}
