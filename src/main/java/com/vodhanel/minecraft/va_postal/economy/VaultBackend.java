package com.vodhanel.minecraft.va_postal.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * Plain Vault (net.milkbowl.vault.economy). Uses player accounts only, never the bank API:
 * EssentialsX, XConomy and TheNewEconomy don't implement banks, and XConomy returns null from them.
 * Office accounts are player accounts held by an {@link NpcAccountHolder}.
 */
final class VaultBackend implements EconomyBackend {
    private final Economy econ;

    VaultBackend(Economy econ) {
        this.econ = econ;
    }

    private static OfflinePlayer holder(UUID id, String name, boolean player) {
        return player ? Bukkit.getOfflinePlayer(id) : NpcAccountHolder.of(id, name);
    }

    private static boolean ok(EconomyResponse response) {
        return response != null && response.transactionSuccess();
    }

    @Override
    public String provider_name() {
        return econ.getName();
    }

    @Override
    public String format(double amount) {
        return econ.format(amount);
    }

    @Override
    public boolean has_account(UUID id) {
        return econ.hasAccount(Bukkit.getOfflinePlayer(id));
    }

    @Override
    public boolean ensure_account(UUID id, String name, boolean player) {
        OfflinePlayer holder = holder(id, name, player);
        return econ.hasAccount(holder) || econ.createPlayerAccount(holder);
    }

    @Override
    public double balance(UUID id, String name, boolean player) {
        OfflinePlayer holder = holder(id, name, player);
        return econ.hasAccount(holder) ? econ.getBalance(holder) : 0.0D;
    }

    @Override
    public boolean has(UUID id, String name, boolean player, double amount) {
        OfflinePlayer holder = holder(id, name, player);
        return econ.hasAccount(holder) && econ.has(holder, amount);
    }

    @Override
    public boolean deposit(UUID id, String name, boolean player, double amount) {
        if (!ensure_account(id, name, player)) {
            return false;
        }
        return ok(econ.depositPlayer(holder(id, name, player), amount));
    }

    @Override
    public boolean withdraw(UUID id, String name, boolean player, double amount) {
        OfflinePlayer holder = holder(id, name, player);
        return econ.hasAccount(holder) && ok(econ.withdrawPlayer(holder, amount));
    }
}
