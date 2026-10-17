package com.vodhanel.minecraft.va_postal.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired (on the main thread) when Postal marks a letter or parcel MISSING: its record says a chest holds it and
 * the chest doesn't, so it was griefed or taken. This is the hook for lost-mail claims and an insurance fund
 * (docs/design/persistent-state.md, phase P2): the record keeps the mail's contents, so it can be recovered
 * (/postal recover) or paid out against.
 */
public class MailMissingEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID mail_id;
    private final String kind;
    private final UUID sender;
    private final String dest_office;
    private final String dest_address;
    private final String last_seen;
    private final String reason;
    private final double cod_amount;

    public MailMissingEvent(UUID mail_id, String kind, UUID sender, String dest_office, String dest_address,
                            String last_seen, String reason, double cod_amount) {
        this.mail_id = mail_id;
        this.kind = kind;
        this.sender = sender;
        this.dest_office = dest_office;
        this.dest_address = dest_address;
        this.last_seen = last_seen;
        this.reason = reason;
        this.cod_amount = cod_amount;
    }

    public UUID getMailId() {
        return mail_id;
    }

    /** LETTER or PARCEL. */
    public String getKind() {
        return kind;
    }

    /** Who sent it (null for mail the server or console sent). */
    public UUID getSender() {
        return sender;
    }

    public String getDestOffice() {
        return dest_office;
    }

    public String getDestAddress() {
        return dest_address;
    }

    /** Where it was last held: {@code CHEST@world,x,y,z}. */
    public String getLastSeen() {
        return last_seen;
    }

    public String getReason() {
        return reason;
    }

    public double getCodAmount() {
        return cod_amount;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
