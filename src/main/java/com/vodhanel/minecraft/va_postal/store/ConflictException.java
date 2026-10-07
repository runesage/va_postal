package com.vodhanel.minecraft.va_postal.store;

/** The record changed since it was read (optimistic lock lost), or the change isn't allowed from its state. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
