package com.vodhanel.minecraft.va_postal.economy;

import java.util.UUID;

/**
 * The few money operations Postal needs, over whichever economy API the server provides.
 * Every account is addressed by UUID; {@code name} is only used to create an account the first time.
 * Implementations never throw for economy-side failures and treat a null response as a failure.
 */
interface EconomyBackend {
    String provider_name();

    String format(double amount);

    boolean has_account(UUID id);

    /** Creates the account if it doesn't exist yet. {@code player} is false for office (NPC) accounts. */
    boolean ensure_account(UUID id, String name, boolean player);

    double balance(UUID id, String name, boolean player);

    boolean has(UUID id, String name, boolean player, double amount);

    boolean deposit(UUID id, String name, boolean player, double amount);

    boolean withdraw(UUID id, String name, boolean player, double amount);
}
