package com.vodhanel.minecraft.va_postal.economy;

import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * VaultUnlocked (net.milkbowl.vault2.economy), which has real non-player accounts.
 * Only load this class after checking that VaultUnlocked is installed.
 */
final class VaultUnlockedBackend implements EconomyBackend {
    private static final String PLUGIN_NAME = "Postal";

    private final Economy econ;

    VaultUnlockedBackend(Economy econ) {
        this.econ = econ;
    }

    private static boolean ok(EconomyResponse response) {
        return response != null && response.transactionSuccess();
    }

    @Override
    public String provider_name() {
        return econ.getName() + " (VaultUnlocked)";
    }

    @Override
    public String format(double amount) {
        return econ.format(BigDecimal.valueOf(amount));
    }

    @Override
    public boolean has_account(UUID id) {
        return econ.hasAccount(id);
    }

    @Override
    public boolean ensure_account(UUID id, String name, boolean player) {
        return econ.hasAccount(id) || econ.createAccount(id, name, player);
    }

    @Override
    public double balance(UUID id, String name, boolean player) {
        if (!econ.hasAccount(id)) {
            return 0.0D;
        }
        BigDecimal balance = econ.getBalance(PLUGIN_NAME, id);
        return balance == null ? 0.0D : balance.doubleValue();
    }

    @Override
    public boolean has(UUID id, String name, boolean player, double amount) {
        return econ.hasAccount(id) && econ.has(PLUGIN_NAME, id, BigDecimal.valueOf(amount));
    }

    @Override
    public boolean deposit(UUID id, String name, boolean player, double amount) {
        if (!ensure_account(id, name, player)) {
            return false;
        }
        return ok(econ.deposit(PLUGIN_NAME, id, BigDecimal.valueOf(amount)));
    }

    @Override
    public boolean withdraw(UUID id, String name, boolean player, double amount) {
        return econ.hasAccount(id) && ok(econ.withdraw(PLUGIN_NAME, id, BigDecimal.valueOf(amount)));
    }
}
