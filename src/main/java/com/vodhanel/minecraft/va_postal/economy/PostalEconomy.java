package com.vodhanel.minecraft.va_postal.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Postal's money layer. Central and each local post office are real accounts in the server economy
 * (see {@link OfficeAccounts}), so their balances show up in baltop, other plugins can pay into them,
 * and nothing depends on Vault's bank API.
 * <p>
 * Backend choice: VaultUnlocked's non-player accounts when VaultUnlocked provides an economy,
 * otherwise plain Vault player accounts held by NPC stand-ins.
 */
public final class PostalEconomy {
    private static EconomyBackend backend;
    private static Logger log = Logger.getLogger("Postal");

    private PostalEconomy() {
    }

    /** Hooks the economy. Returns false (economy stays off) when no usable provider is registered. */
    public static synchronized boolean setup(Plugin plugin) {
        backend = null;
        log = plugin.getLogger();
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            log.warning("Economy is enabled in config, but Vault/VaultUnlocked is not installed. Economy disabled.");
            return false;
        }
        EconomyBackend chosen = vault_unlocked(plugin);
        if (chosen == null) {
            chosen = legacy_vault(plugin);
        }
        if (chosen == null) {
            log.warning("Vault is installed, but no economy plugin is registered with it. Economy disabled.");
            return false;
        }
        backend = chosen;
        log.info("Using " + backend.provider_name() + " for economy.");
        return true;
    }

    private static EconomyBackend vault_unlocked(Plugin plugin) {
        try {
            Class.forName("net.milkbowl.vault2.economy.Economy");
        } catch (ClassNotFoundException e) {
            return null;
        }
        RegisteredServiceProvider<net.milkbowl.vault2.economy.Economy> rsp =
                plugin.getServer().getServicesManager().getRegistration(net.milkbowl.vault2.economy.Economy.class);
        return rsp == null || rsp.getProvider() == null ? null : new VaultUnlockedBackend(rsp.getProvider());
    }

    private static EconomyBackend legacy_vault(Plugin plugin) {
        try {
            Class.forName("net.milkbowl.vault.economy.Economy");
        } catch (ClassNotFoundException e) {
            return null;
        }
        RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                plugin.getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        return rsp == null || rsp.getProvider() == null ? null : new VaultBackend(rsp.getProvider());
    }

    public static synchronized void shutdown() {
        backend = null;
    }

    public static boolean is_enabled() {
        return backend != null;
    }

    public static String format(double amount) {
        EconomyBackend b = backend;
        return b == null ? String.format("%.2f", amount) : b.format(amount);
    }

    // ---- Office accounts -------------------------------------------------------------------

    public static boolean ensure_central() {
        return ensure_npc(OfficeAccounts.central_id(), OfficeAccounts.central_name());
    }

    public static boolean ensure_office(String office) {
        return ensure_npc(OfficeAccounts.office_id(office), OfficeAccounts.office_name(office));
    }

    public static boolean does_office_exist(String office) {
        EconomyBackend b = backend;
        return b != null && b.has_account(OfficeAccounts.office_id(office));
    }

    public static double central_balance() {
        return npc_balance(OfficeAccounts.central_id(), OfficeAccounts.central_name());
    }

    public static double office_balance(String office) {
        return npc_balance(OfficeAccounts.office_id(office), OfficeAccounts.office_name(office));
    }

    public static boolean central_has(double amount) {
        EconomyBackend b = backend;
        return b != null && b.has(OfficeAccounts.central_id(), OfficeAccounts.central_name(), false, amount);
    }

    public static boolean office_has(String office, double amount) {
        EconomyBackend b = backend;
        return b != null && b.has(OfficeAccounts.office_id(office), OfficeAccounts.office_name(office), false, amount);
    }

    public static boolean deposit_central(double amount) {
        return move(OfficeAccounts.central_id(), OfficeAccounts.central_name(), false, amount, true);
    }

    public static boolean withdraw_central(double amount) {
        return move(OfficeAccounts.central_id(), OfficeAccounts.central_name(), false, amount, false);
    }

    public static boolean deposit_office(String office, double amount) {
        return move(OfficeAccounts.office_id(office), OfficeAccounts.office_name(office), false, amount, true);
    }

    public static boolean withdraw_office(String office, double amount) {
        return move(OfficeAccounts.office_id(office), OfficeAccounts.office_name(office), false, amount, false);
    }

    // ---- Player accounts -------------------------------------------------------------------

    public static boolean ensure_player(OfflinePlayer player) {
        EconomyBackend b = backend;
        return b != null && player != null && b.ensure_account(player.getUniqueId(), player.getName(), true);
    }

    public static boolean has_player_account(OfflinePlayer player) {
        EconomyBackend b = backend;
        return b != null && player != null && b.has_account(player.getUniqueId());
    }

    public static double player_balance(OfflinePlayer player) {
        EconomyBackend b = backend;
        return b == null || player == null ? 0.0D : b.balance(player.getUniqueId(), player.getName(), true);
    }

    public static boolean player_has(OfflinePlayer player, double amount) {
        EconomyBackend b = backend;
        return b != null && player != null && b.has(player.getUniqueId(), player.getName(), true, amount);
    }

    public static boolean deposit_player(OfflinePlayer player, double amount) {
        return player != null && move(player.getUniqueId(), player.getName(), true, amount, true);
    }

    public static boolean withdraw_player(OfflinePlayer player, double amount) {
        return player != null && move(player.getUniqueId(), player.getName(), true, amount, false);
    }

    // ---- Transfers -------------------------------------------------------------------------

    /**
     * Moves money from central to a local office. If the deposit fails the withdrawal is refunded,
     * so money is never destroyed by a half-finished transfer.
     */
    public static boolean central_to_office(String office, double amount) {
        if (amount <= 0.0D) {
            return true;
        }
        if (!withdraw_central(amount)) {
            return false;
        }
        if (deposit_office(office, amount)) {
            return true;
        }
        if (!deposit_central(amount)) {
            log.severe("Lost " + format(amount) + " moving Central -> " + office + ": deposit and refund both failed.");
        }
        return false;
    }

    /** Moves money from a local office to central, refunding the office if the deposit fails. */
    public static boolean office_to_central(String office, double amount) {
        if (amount <= 0.0D) {
            return true;
        }
        if (!withdraw_office(office, amount)) {
            return false;
        }
        if (deposit_central(amount)) {
            return true;
        }
        if (!deposit_office(office, amount)) {
            log.severe("Lost " + format(amount) + " moving " + office + " -> Central: deposit and refund both failed.");
        }
        return false;
    }

    // ---- Internals -------------------------------------------------------------------------

    private static boolean ensure_npc(UUID id, String name) {
        EconomyBackend b = backend;
        return b != null && b.ensure_account(id, name, false);
    }

    private static double npc_balance(UUID id, String name) {
        EconomyBackend b = backend;
        return b == null ? 0.0D : b.balance(id, name, false);
    }

    private static boolean move(UUID id, String name, boolean player, double amount, boolean deposit) {
        EconomyBackend b = backend;
        if (b == null || amount < 0.0D || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return false;
        }
        if (amount == 0.0D) {
            return true;
        }
        try {
            return deposit ? b.deposit(id, name, player, amount) : b.withdraw(id, name, player, amount);
        } catch (RuntimeException e) {
            log.warning("Economy provider failed to " + (deposit ? "deposit to " : "withdraw from ") + name + ": " + e);
            return false;
        }
    }
}
