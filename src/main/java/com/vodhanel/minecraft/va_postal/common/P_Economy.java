package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.economy.PostalEconomy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Postal's business rules for money: who pays what, and how it is split between Central and the
 * local offices. All account access goes through {@link PostalEconomy}; Central and every local
 * office are real accounts in the server economy, not Vault banks.
 */
public class P_Economy {
    /** Seconds between distributions of Central's surplus to the local offices. */
    private static final int DISTRIBUTION_INTERVAL = 1200;

    public static int last_central_dist = 0;
    VA_postal plugin;

    public P_Economy(VA_postal instance) {
        plugin = instance;
    }

    public static void init_economy() {
        last_central_dist = Util.time_stamp();
    }

    /**
     * Every {@link #DISTRIBUTION_INTERVAL} seconds, Central keeps one post office purchase price in
     * reserve (to fund ownership refunds) and splits the rest evenly between the local offices.
     */
    public static void ping_economy_schedule() {
        if (!VA_postal.economy_configured) {
            return;
        }
        if (Util.time_stamp() - last_central_dist <= DISTRIBUTION_INTERVAL) {
            return;
        }
        last_central_dist = Util.time_stamp();
        verify_central();

        double retension = C_Economy.po_purchase_price();
        double central_balance = central_balance();
        if (central_balance <= retension) {
            return;
        }
        String[] town_list = C_Arrays.town_list();
        if ((town_list == null) || (town_list.length <= 0)) {
            return;
        }

        double po_share = (central_balance - retension) / town_list.length;
        double transfered = 0.0D;
        Util.cinform("\033[0;37m[Postal] ============================================");
        Util.cinform("\033[0;33m[Postal] Daily distribution of central proceeds......");
        Util.cinform("\033[0;33m[Postal] Beginning central balance ------ \033[0;37m" + fixed_len_rt(ef(central_balance), 10));
        Util.cinform("\033[0;33m[Postal] Local post office share -------- \033[0;37m" + fixed_len_rt(ef(po_share), 10));
        Util.cinform("\033[0;33m[Postal] New local balances:");
        for (String stown : town_list) {
            if (does_the_bank_exist(stown) && PostalEconomy.central_to_office(stown, po_share)) {
                transfered += po_share;
                String new_bal = fixed_len_rt(ef(local_balance(stown)), 10);
                String f_postoffice = fixed_len(Util.df(stown), 16, " ");
                Util.cinform("\033[0;33m[Postal]    " + f_postoffice + "  " + AnsiColor.WHITE + new_bal);
            }
        }
        Util.cinform("\033[0;33m[Postal] Ending  central  balance  ------ \033[0;37m" + fixed_len_rt(ef(central_balance - transfered), 10));
        Util.cinform("\033[0;37m[Postal] ============================================");
    }

    // ---- Office accounts -------------------------------------------------------------------

    public static void create_bank(String sbank) {
        if (!PostalEconomy.ensure_office(sbank)) {
            Util.cinform(AnsiColor.RED + "[Postal] Problem creating economy account for " + Util.df(sbank));
        }
    }

    public static void create_central() {
        if (!PostalEconomy.ensure_central()) {
            Util.cinform(AnsiColor.RED + "[Postal] Problem creating economy account for Central");
        }
    }

    public static boolean does_the_bank_exist(String sbank) {
        return PostalEconomy.does_office_exist(sbank);
    }

    public static boolean does_central_have_amount(double amount) {
        return PostalEconomy.central_has(amount);
    }

    public static boolean does_local_have_amount(String local_po, double amount) {
        return PostalEconomy.office_has(local_po, amount);
    }

    public static double central_balance() {
        return PostalEconomy.central_balance();
    }

    public static double local_balance(String local_po) {
        return PostalEconomy.office_balance(local_po);
    }

    public static void deposit_to_central(double amount) {
        if (!PostalEconomy.deposit_central(amount)) {
            Util.cinform(AnsiColor.RED + "[Postal] Problem depositing " + ef(amount) + " to Central");
        }
    }

    public static boolean deposit_to_local(String local_po, double amount) {
        if (local_po == null) {
            deposit_to_central(amount);
            return false;
        }
        if (!PostalEconomy.deposit_office(local_po, amount)) {
            Util.cinform(AnsiColor.RED + "[Postal] Problem depositing " + ef(amount) + " to " + Util.df(local_po));
            return false;
        }
        return true;
    }

    public static boolean withdraw_from_central(double amount) {
        if (!PostalEconomy.withdraw_central(amount)) {
            Util.cinform(AnsiColor.RED + "[Postal] Problem withdrawing " + ef(amount) + " from Central");
            return false;
        }
        return true;
    }

    public static boolean withdraw_from_local(String local_po, double amount) {
        if (!PostalEconomy.withdraw_office(local_po, amount)) {
            Util.cinform(AnsiColor.RED + "[Postal] Problem withdrawing " + ef(amount) + " from " + Util.df(local_po));
            return false;
        }
        return true;
    }

    public static void verify_bank(String stown) {
        if (!VA_postal.economy_configured) {
            return;
        }
        if (!does_the_bank_exist(stown)) {
            create_bank(stown);
            // Central funds the refund an owner gets when the office changes hands; back it on creation.
            if (C_Owner.get_owner_local_po_id(stown) != null) {
                deposit_to_central(C_Economy.po_purchase_price());
            }
        }
    }

    public static void verify_central() {
        if (VA_postal.economy_configured) {
            create_central();
        }
    }

    // ---- Player accounts -------------------------------------------------------------------

    public static boolean does_player_have_account(OfflinePlayer player) {
        return PostalEconomy.has_player_account(player);
    }

    public static void create_player_account(OfflinePlayer player) {
        if (!PostalEconomy.ensure_player(player)) {
            Util.cinform(AnsiColor.RED + "[Postal] Problem creating account for " + name(player));
        }
    }

    public static boolean does_player_have_amount(OfflinePlayer player, double amount) {
        return PostalEconomy.player_has(player, amount);
    }

    public static double player_balance(OfflinePlayer player) {
        return PostalEconomy.player_balance(player);
    }

    public static boolean deposit_to_player(OfflinePlayer player, double amount) {
        if (!PostalEconomy.deposit_player(player, amount)) {
            Util.cinform(AnsiColor.RED + "[Postal] Problem depositing " + ef(amount) + " to " + name(player));
            return false;
        }
        return true;
    }

    public static boolean withdraw_from_player(OfflinePlayer player, double amount) {
        if (!PostalEconomy.withdraw_player(player, amount)) {
            Util.cinform(AnsiColor.RED + "[Postal] Problem withdrawing " + ef(amount) + " from " + name(player));
            return false;
        }
        return true;
    }

    // ---- Ownership -------------------------------------------------------------------------

    /** True if {@code player} owns the office; with no owner set, only the server "owns" it. */
    public static boolean is_the_bank_owner(String sbank, Player player) {
        return same_owner(C_Owner.get_owner_local_po_id(sbank), owner_id(player));
    }

    public static Player get_bank_owner(String sbank) {
        if (C_Owner.is_local_po_owner_defined(sbank)) {
            return C_Owner.get_owner_local_po(sbank);
        }
        return VA_postal.SERVER;
    }

    /** UUID of an owner, with the server (or nobody) represented as null. */
    private static UUID owner_id(Player owner) {
        if (owner == null || VA_postal.SERVER_ID.equals(owner.getUniqueId())) {
            return null;
        }
        return owner.getUniqueId();
    }

    private static boolean same_owner(UUID a, UUID b) {
        return a == null ? b == null : a.equals(b);
    }

    // ---- Prices ----------------------------------------------------------------------------

    public static double has_price_of_postage(Player player, String dest_po) {
        if (!VA_postal.economy_configured) {
            return 0.0D;
        }
        double price = C_Economy.postage_price(dest_po.equalsIgnoreCase(get_local(player)));
        return does_player_have_amount(player, price) ? price : -1.0D;
    }

    public static double has_price_of_shipping(Player player, String dest_po) {
        if (!VA_postal.economy_configured) {
            return 0.0D;
        }
        double price = C_Economy.ship_price(dest_po.equalsIgnoreCase(get_local(player)));
        return does_player_have_amount(player, price) ? price : -1.0D;
    }

    public static double has_price_of_cod(Player player) {
        if (!VA_postal.economy_configured) {
            return 0.0D;
        }
        double price = C_Economy.cod_surchg();
        return does_player_have_amount(player, price) ? price : -1.0D;
    }

    public static double has_price_of_distr(Player player, String modifier, String stown) {
        if (!VA_postal.economy_configured) {
            return 0.0D;
        }
        int dist_count = dist_count(modifier, stown);
        if (dist_count == -1) {
            return -10.0D;
        }
        double price = C_Economy.distr_price() * dist_count;
        return does_player_have_amount(player, price) ? price : -1.0D;
    }

    public static double has_price_of_postoffice(Player player) {
        if (!VA_postal.economy_configured) {
            return 0.0D;
        }
        double price = C_Economy.po_purchase_price();
        return does_player_have_amount(player, price) ? price : -1.0D;
    }

    public static double has_price_of_address(Player player) {
        if (!VA_postal.economy_configured) {
            return 0.0D;
        }
        double price = C_Economy.addr_purchase_price();
        return does_player_have_amount(player, price) ? price : -1.0D;
    }

    public static boolean can_central_buy_po() {
        return central_balance() > C_Economy.po_purchase_price();
    }

    public static boolean can_central_buy_addr() {
        return central_balance() > C_Economy.addr_purchase_price();
    }

    public static int dist_count(String modifier, String srch_stown) {
        if ("[all]".equals(modifier)) {
            modifier = "all_addresses";
        }
        if ("[all]".equals(srch_stown)) {
            srch_stown = "all_towns";
        }
        int count = 0;
        String[] town_list = C_Arrays.town_list();
        if (town_list == null) {
            Util.cinform("Problem getting town array.");
            return -1;
        }
        for (String stown : town_list) {
            if (!"all_towns".equalsIgnoreCase(srch_stown) && !stown.equalsIgnoreCase(srch_stown)) {
                continue;
            }
            String[] addr_list = C_Arrays.addresses_list(stown);
            if (addr_list == null) {
                continue;
            }
            for (String saddress : addr_list) {
                if ("all_addresses".equalsIgnoreCase(modifier) || C_Owner.is_address_owner_defined(stown, saddress)) {
                    count++;
                }
            }
        }
        return count;
    }

    // ---- Charges ---------------------------------------------------------------------------

    public static void charge_distr(Player player, String modifier, String stown) {
        if (!VA_postal.economy_configured) {
            return;
        }
        int dist_count = dist_count(modifier, stown);
        if (dist_count == -1) {
            return;
        }
        double price = C_Economy.distr_price() * dist_count;
        if (withdraw_from_player(player, price)) {
            Util.pinform(player, "&6Thank you for your payment.");
            deposit_to_central(price);
        } else {
            Util.cinform(AnsiColor.RED + "[Postal] Problem charging " + name(player) + " for distribution.");
        }
    }

    /** Letter postage: split between Central and the sending office, plus the destination office if different. */
    public static void charge_postage(Player player, String dest_po) {
        if (VA_postal.economy_configured) {
            charge_and_split(player, dest_po, false);
        }
    }

    /** Parcel shipping: same split as postage, at shipping prices. */
    public static void charge_shipping(Player player, String dest_po) {
        if (VA_postal.economy_configured) {
            charge_and_split(player, dest_po, true);
        }
    }

    private static void charge_and_split(Player player, String dest_po, boolean shipment) {
        String loc_po = get_local(player);
        if (loc_po == null) {
            loc_po = dest_po;
        }
        boolean local = loc_po.equalsIgnoreCase(dest_po);
        double price = shipment ? C_Economy.ship_price(local) : C_Economy.postage_price(local);

        if (!withdraw_from_player(player, price)) {
            Util.cinform(AnsiColor.RED + "[Postal] Problem charging " + name(player) + (shipment ? " for shipping." : " for postage."));
            return;
        }
        Util.pinform(player, "&6Thank you for your payment.");
        if (local) {
            double dist = price / 2.0D;
            deposit_to_central(price - dist);
            deposit_to_local(loc_po, dist);
        } else {
            double dist = price / 3.0D;
            deposit_to_central(price - 2.0D * dist);
            deposit_to_local(loc_po, dist);
            deposit_to_local(dest_po, dist);
        }
    }

    public static void charge_cod_surcharge(Player player) {
        if (!VA_postal.economy_configured) {
            return;
        }
        double price = C_Economy.cod_surchg();
        if (withdraw_from_player(player, price)) {
            Util.pinform(player, "&6Thank you for your payment.");
            double dist = price / 2.0D;
            deposit_to_central(price - dist);
            deposit_to_local(get_local(player), dist);
        }
    }

    /** Charges a player directly (used for COD). Returns true only if the money was actually taken. */
    public static boolean charge_player(Player player, double amount) {
        if (!VA_postal.economy_configured) {
            return false;
        }
        if (withdraw_from_player(player, amount)) {
            Util.pinform(player, "&6Thank you for your payment.");
            return true;
        }
        return false;
    }

    public static void pay_player(OfflinePlayer player, double amount) {
        if (VA_postal.economy_configured) {
            create_player_account(player);
            deposit_to_player(player, amount);
        }
    }

    /**
     * Charges {@code subject} the post office purchase price and makes them the owner. The price
     * goes to Central, which holds it to refund the subject if ownership later changes.
     */
    public static double charge_po_purchase(Player player, Player subject, String dest_po) {
        UUID subject_id = owner_id(subject);
        if (subject_id == null) {
            synchronize_bank_owner(player, dest_po, VA_postal.SERVER);
            return 0.0D;
        }
        if (subject_id.equals(C_Owner.get_owner_local_po_id(dest_po))) {
            synchronize_bank_owner(player, dest_po, subject);
            inform(player, "[Postal] player " + name(subject) + " already owns post office " + Util.df(dest_po));
            return 0.0D;
        }

        double price = C_Economy.po_purchase_price();
        if (withdraw_from_player(subject, price)) {
            Util.cinform("\033[0;33m[Postal] Withdrawn " + ef(price) + " from player " + name(subject));
            deposit_to_central(price);
            Util.cinform("\033[0;32m[Postal] Deposited " + ef(price) + " to Central");
            synchronize_bank_owner(player, dest_po, subject);
            return price;
        }
        Util.cinform(AnsiColor.RED + "[Postal] Problem charging " + name(subject) + " for PO purchase.");
        return 0.0D;
    }

    public static void synchronize_bank_owner(Player player, String stown, Player owner) {
        if ((stown == null) || (owner == null)) {
            return;
        }
        UUID new_owner = owner_id(owner);
        UUID existing_owner = C_Owner.get_owner_local_po_id(stown);

        sync_econ_bank_owner(stown, existing_owner, new_owner);
        if (same_owner(existing_owner, new_owner)) {
            return;
        }
        if (new_owner == null) {
            C_Owner.del_owner_local_po(stown);
            inform(player, "Owner removed from: " + Util.df(stown));
        } else {
            C_Owner.set_owner_local_po(stown, owner);
            inform(player, Util.df(stown) + " is now owned by " + name(owner));
        }
    }

    /**
     * Settles the office account when ownership changes: a player owner gets the office balance and
     * their purchase price back (from Central's reserve); a server-owned office's balance goes to
     * Central. The office account then starts from zero for the new owner.
     */
    private static void sync_econ_bank_owner(String stown, UUID existing_owner, UUID new_owner) {
        if (!VA_postal.economy_configured) {
            return;
        }
        if (!does_the_bank_exist(stown)) {
            create_bank(stown);
            return;
        }
        if (same_owner(existing_owner, new_owner)) {
            return;
        }

        double existing_balance = Math.max(0.0D, local_balance(stown));
        if (existing_owner == null) {
            if (existing_balance > 0.0D && PostalEconomy.office_to_central(stown, existing_balance)) {
                Util.cinform("\033[0;32m[Postal] Balance of " + ef(existing_balance) + " moved to Central for distribution");
            }
            return;
        }

        OfflinePlayer previous = Bukkit.getOfflinePlayer(existing_owner);
        create_player_account(previous);
        if (existing_balance > 0.0D && withdraw_from_local(stown, existing_balance)) {
            if (deposit_to_player(previous, existing_balance)) {
                Util.cinform("\033[0;33m[Postal] Balance of " + ef(existing_balance) + " from " + Util.df(stown) + " paid to " + name(previous));
            } else {
                deposit_to_central(existing_balance);
                Util.cinform("\033[0;32m[Postal] Balance of " + ef(existing_balance) + " moved to Central for distribution");
            }
        }

        double price = C_Economy.po_purchase_price();
        if (price > 0.0D) {
            if (withdraw_from_central(price)) {
                if (deposit_to_player(previous, price)) {
                    Util.cinform("\033[0;32m[Postal] Purchase price of " + ef(price) + " refunded to " + name(previous));
                } else {
                    deposit_to_central(price);
                }
            } else {
                Util.cinform(AnsiColor.RED + "[Postal] Central cannot cover the " + ef(price) + " refund to " + name(previous));
            }
        }
    }

    /**
     * Charges {@code subject} the address price and makes them the owner. Half goes to Central,
     * half to the address's local office; both halves are refunded if the address changes hands.
     */
    public static double charge_addr_purchase(Player player, Player subject, String dest_po, String dest_addr) {
        UUID subject_id = owner_id(subject);
        if (subject_id == null) {
            synchronize_addr_owner(player, dest_po, dest_addr, VA_postal.SERVER);
            return 0.0D;
        }
        if (subject_id.equals(C_Owner.get_owner_address_id(dest_po, dest_addr))) {
            synchronize_addr_owner(player, dest_po, dest_addr, subject);
            inform(player, "[Postal] player " + name(subject) + " already owns " + Util.df(dest_po) + ", " + Util.df(dest_addr));
            return 0.0D;
        }

        double price = C_Economy.addr_purchase_price();
        if (withdraw_from_player(subject, price)) {
            Util.cinform("\033[0;33m[Postal] Withdrawn " + ef(price) + " from player " + name(subject));
            double dist = price / 2.0D;
            deposit_to_central(price - dist);
            Util.cinform("\033[0;32m[Postal] Deposited " + ef(price - dist) + " to Central");
            deposit_to_local(dest_po, dist);
            Util.cinform("\033[0;32m[Postal] Deposited " + ef(dist) + " to local " + Util.df(dest_po));
            synchronize_addr_owner(player, dest_po, dest_addr, subject);
            return price;
        }
        Util.cinform(AnsiColor.RED + "[Postal] Problem charging " + name(subject) + " for address purchase.");
        return 0.0D;
    }

    public static void synchronize_addr_owner(Player player, String stown, String saddr, Player owner) {
        if ((stown == null) || (saddr == null)) {
            return;
        }
        UUID new_owner = owner_id(owner);
        UUID existing_owner = C_Owner.get_owner_address_id(stown, saddr);
        if (same_owner(existing_owner, new_owner)) {
            return;
        }

        refund_addr_owner(stown, existing_owner);
        if (new_owner == null) {
            C_Owner.del_owner_address(stown, saddr);
            inform(player, "Owner removed from: " + Util.df(stown) + ", " + Util.df(saddr));
        } else {
            C_Owner.set_owner_address(stown, saddr, owner);
            inform(player, Util.df(stown) + ", " + Util.df(saddr) + " now owned by " + name(owner));
        }
    }

    /** Refunds the previous address owner's purchase price, half from Central and half from the local office. */
    private static void refund_addr_owner(String stown, UUID existing_owner) {
        if (!VA_postal.economy_configured || existing_owner == null) {
            return;
        }
        double price = C_Economy.addr_purchase_price();
        if (price <= 0.0D) {
            return;
        }
        double dist = price / 2.0D;
        if (!withdraw_from_central(price - dist)) {
            Util.cinform(AnsiColor.RED + "[Postal] Central cannot cover the address refund for " + Util.df(stown));
            return;
        }
        if (!withdraw_from_local(stown, dist)) {
            deposit_to_central(price - dist);
            Util.cinform(AnsiColor.RED + "[Postal] " + Util.df(stown) + " cannot cover its half of the address refund");
            return;
        }
        OfflinePlayer previous = Bukkit.getOfflinePlayer(existing_owner);
        create_player_account(previous);
        if (deposit_to_player(previous, price)) {
            Util.cinform("\033[0;32m[Postal] Address price of " + ef(price) + " refunded to " + name(previous));
        } else {
            deposit_to_central(price - dist);
            deposit_to_local(stown, dist);
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    /** Nearest local post office to the player, or null if none could be determined. */
    public static String get_local(Player player) {
        String[] list = C_Arrays.geo_po_list_sorted(player);
        if ((list != null) && (list.length > 0)) {
            String[] parts = list[0].split(",");
            if (parts.length > 1) {
                return parts[1].trim();
            }
        }
        Util.cinform(AnsiColor.RED + "[Postal] Problem splitting local PO geo list to calculate postage. ");
        return null;
    }

    private static String name(OfflinePlayer player) {
        if (player == null) {
            return "Server";
        }
        String name = player.getName();
        return name != null ? name : player.getUniqueId().toString();
    }

    private static void inform(Player player, String message) {
        if (player == null) {
            Util.con_type(message);
        } else {
            Util.pinform(player, message);
        }
    }

    public static String ef(double value) {
        if (VA_postal.economy_configured) {
            return PostalEconomy.format(value);
        }
        return "-1";
    }

    public static String proper(String string) {
        try {
            if (string.length() > 0) {
                return string.substring(0, 1).toUpperCase() + string.substring(1).toLowerCase().trim();
            }
        } catch (Exception e) {
        }

        return "";
    }

    public static synchronized String fixed_len(String input, int len, String filler) {
        try {
            input = input.trim();

            if (input.length() >= len) {
                return input.substring(0, len);
            }

            while (input.length() < len) {
                input = input + filler;
            }
            return input;
        } catch (Exception e) {
            String blank = "";
            for (int i = 0; i < len; i++) {
                blank = blank + filler;
            }
            return blank;
        }
    }

    public static synchronized String fixed_len_rt(String input, int len) {
        String filler = " ";
        try {
            input = input.trim();

            if (input.length() >= len) {
                return input.substring(0, len);
            }

            while (input.length() < len) {
                input = filler + input;
            }
            return input;
        } catch (Exception e) {
            String blank = "";
            for (int i = 0; i < len; i++) {
                blank = blank + filler;
            }
            return blank;
        }
    }
}
