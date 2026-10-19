package com.vodhanel.minecraft.va_postal.store;

/**
 * One office's numbers for one day (docs/design/central-office.md §7): letters and parcels it picked up ({@code sent}),
 * delivered, delivered on time, lost (gone MISSING while it held them), and the summed time from pickup to delivery.
 * {@code day} counts UTC days since the epoch.
 */
public record OfficeDay(String server_id, String office, int day, int sent, int delivered, int on_time, int lost, long transit_ms) {
}
