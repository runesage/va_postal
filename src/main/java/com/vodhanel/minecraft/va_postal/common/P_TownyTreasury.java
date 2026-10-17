package com.vodhanel.minecraft.va_postal.common;

import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.object.economy.Account;
import com.palmergames.bukkit.towny.object.economy.TownyServerAccount;
import com.vodhanel.minecraft.va_postal.economy.PostalEconomy;

/**
 * Opt-in shared treasury (docs/design/economy.md §9): with {@code Economy.Central_account: towny} and Towny's
 * closed economy enabled, Central is Towny's server account, so Postal's fees and upkeep pool with Towny's
 * taxes. Only loaded when Towny is installed.
 */
final class P_TownyTreasury {
    private P_TownyTreasury() {
    }

    /** Points Central at Towny's server account. Returns why it couldn't, or null on success. */
    static String use_towny_server_account() {
        if (!TownySettings.isEcoClosedEconomyEnabled()) {
            return "Towny's closed economy is off (economy.closed_economy.enabled in Towny's config)";
        }
        Account account = TownyServerAccount.ACCOUNT;
        if (account == null || account.getUUID() == null) {
            return "Towny has no server account";
        }
        PostalEconomy.use_central_account(account.getUUID(), account.getName());
        return null;
    }
}
