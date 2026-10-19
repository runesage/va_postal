package com.vodhanel.minecraft.va_postal.store;

/**
 * A change of state the stats roll-up counts (docs/design/central-office.md §7): a piece of mail picked up
 * ({@code AT_ORIGIN_BRANCH}), delivered ({@code DELIVERED}) or lost ({@code MISSING}) on this server, with what it
 * needs to attribute it to an office. {@code picked_up_at} is when it was first picked up (its creation if never).
 */
public record MailFact(String mail_id, String origin_server, String origin_office, String dest_office, String dest_address,
                       long picked_up_at, long at, MailState from, MailState to) {
}
