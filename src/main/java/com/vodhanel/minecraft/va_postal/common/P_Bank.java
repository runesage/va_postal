package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.economy.PostalDay;
import com.vodhanel.minecraft.va_postal.economy.Reserves;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The state of Postal's accounts as the economy design sees it (docs/design/economy.md): balances,
 * liabilities, reserves and what owners may withdraw, plus the admin {@code /postal bank} view.
 */
public final class P_Bank {
    private P_Bank() {
    }

    /** One local office's position. {@code owner} is null for a server-owned office. */
    public static final class Office {
        public final String name;
        public final UUID owner;
        public final int player_owned_addresses;
        public final double balance;
        public final double liability;
        public final double reserve;

        Office(String name, UUID owner, int player_owned_addresses, double balance, double liability, double reserve) {
            this.name = name;
            this.owner = owner;
            this.player_owned_addresses = player_owned_addresses;
            this.balance = balance;
            this.liability = liability;
            this.reserve = reserve;
        }

        /** What the owner may take out now (0 for server-owned offices, whose surplus goes to Central). */
        public double withdrawable() {
            return owner == null ? 0.0D : Reserves.withdrawable(balance, reserve);
        }
    }

    /** All local offices, by name, as configured under Postoffice.Local. */
    public static List<String> offices() {
        List<String> result = new ArrayList<>();
        try {
            ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("postoffice.local"));
            if (cs != null) {
                result.addAll(cs.getKeys(false));
            }
        } catch (Exception ignored) {
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public static int player_owned_addresses(String office) {
        String[] addresses = C_Arrays.addresses_list(office);
        int count = 0;
        if (addresses != null) {
            for (String address : addresses) {
                if (C_Owner.get_owner_address_id(office, address) != null) {
                    count++;
                }
            }
        }
        return count;
    }

    public static Office office(String name) {
        UUID owner = C_Owner.get_owner_local_po_id(name);
        int owned = player_owned_addresses(name);
        double liability = Reserves.office_liability(owned, C_Economy.addr_purchase_price());
        double reserve = Reserves.office_reserve(liability, C_Economy.office_floor());
        double balance = P_Economy.does_the_bank_exist(name) ? P_Economy.local_balance(name) : 0.0D;
        return new Office(name, owner, owned, balance, liability, reserve);
    }

    /** Central's refunds: office prices of player-owned offices plus its half of player-owned addresses. */
    public static double central_liability() {
        int owned_offices = 0;
        int owned_addresses = 0;
        for (String office : offices()) {
            if (C_Owner.get_owner_local_po_id(office) != null) {
                owned_offices++;
            }
            owned_addresses += player_owned_addresses(office);
        }
        return Reserves.central_liability(owned_offices, C_Economy.po_purchase_price(),
                owned_addresses, C_Economy.addr_purchase_price());
    }

    public static double central_target() {
        return Reserves.central_target(central_liability(), C_Economy.central_buffer());
    }

    // ---- /postal bank ----------------------------------------------------------------------

    /** {@code /postal bank [newday]} for admins, from a player or the console. */
    public static void command(CommandSender sender, String[] args) {
        if (!VA_postal.economy_configured) {
            send(sender, "&7Economy is not enabled (Economy.Use in config.yml).");
            return;
        }
        if (args.length > 1 && "newday".equalsIgnoreCase(args[1])) {
            send(sender, "&7Running a Postal day now.");
            PostalDay.run_now();
            return;
        }
        if (args.length > 1) {
            send(sender, "&7Usage: /postal bank [newday]");
            return;
        }
        report(sender);
    }

    public static void report(CommandSender sender) {
        double central_balance = P_Economy.central_balance();
        double central_liability = central_liability();
        send(sender, "&6[Postal] Bank &7(next Postal day " + next_day() + ")");
        send(sender, "&eCentral &7balance &f" + money(central_balance) + " &7owes &f" + money(central_liability)
                + " &7target &f" + money(Reserves.central_target(central_liability, C_Economy.central_buffer()))
                + (central_balance < central_liability ? " &c(below what it owes)" : ""));
        List<String> offices = offices();
        if (offices.isEmpty()) {
            send(sender, "&7No local post offices.");
            return;
        }
        send(sender, "&7Office: owner | balance | reserve (owes + floor) | withdrawable");
        for (String name : offices) {
            Office o = office(name);
            String owner = o.owner == null ? "Server" : owner_name(o.owner);
            String withdrawable = o.owner == null ? "-" : money(o.withdrawable());
            // For server-owned offices the floor is only what they keep before the daily sweep, not a requirement.
            String flag = (o.owner != null && o.balance < o.reserve) ? " &c(below reserve)" : "";
            send(sender, "&e" + Util.df(o.name) + "&7: " + owner + " | &f" + money(o.balance) + "&7 | "
                    + money(o.reserve) + " (" + money(o.liability) + " + " + money(C_Economy.office_floor()) + ") | &f"
                    + withdrawable + flag);
        }
    }

    private static String next_day() {
        long seconds = PostalDay.seconds_to_next_day();
        if (seconds < 0L) {
            return "not scheduled";
        }
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        return "in " + (hours > 0 ? hours + "h " : "") + minutes + "m";
    }

    private static String owner_name(UUID id) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(id);
        return player.getName() != null ? player.getName() : id.toString();
    }

    private static String money(double amount) {
        return P_Economy.ef(amount);
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
