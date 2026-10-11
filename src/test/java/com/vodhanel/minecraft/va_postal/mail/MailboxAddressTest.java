package com.vodhanel.minecraft.va_postal.mail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailboxAddressTest {
    @Test
    void mailForThisTownsAddressIsForHere() {
        assertTrue(Book.addressed_to("Testville", "Home", "testville", "home"));
        assertTrue(Book.addressed_to("§cTestville", " Home ", "Testville", "Home"));
    }

    @Test
    void anotherTownsAddressOfTheSameNameIsOutgoing() {
        // Posted in Testville's Home mailbox for Riverside's Home: the postman must take it.
        assertFalse(Book.addressed_to("Riverside", "Home", "testville", "home"));
        // The same address on another server's office of the same name (persistent-state P4).
        assertFalse(Book.addressed_to("beta:Testville", "Home", "testville", "home"));
    }

    @Test
    void anotherAddressIsOutgoing() {
        assertFalse(Book.addressed_to("Testville", "Bakery", "testville", "home"));
    }
}
