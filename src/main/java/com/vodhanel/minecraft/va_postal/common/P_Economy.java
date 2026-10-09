package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.economy.EconomyState;
import com.vodhanel.minecraft.va_postal.economy.Hold;
import com.vodhanel.minecraft.va_postal.economy.Postage;
import com.vodhanel.minecraft.va_postal.mail.HoldTag;
import com.vodhanel.minecraft.va_postal.economy.PostalEconomy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Postal's business rules for money: who pays what, and how it is split between Central and the
 * local offices. All account access goes through {@link PostalEconomy}; Central and every local
 * office are real accounts in the server economy, not Vault banks.
 */
public class P_Economy {
    VA_postal plugin;

    public P_Economy(VA_postal instance) {
        plugin = instance;
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
        // Only creates the account. v4 also deposited an office price into Central here when the office had
        // an owner, minting money each time the account was (re)created; Central's refund money comes
        // only from the purchase itself (closed economy, docs/design/economy.md).
        if (!does_the_bank_exist(stown)) {
            create_bank(stown);
            P_Day.seed_server_office(stown);
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
            EconomyState.record(EconomyState.Flow.DISTRIBUTION, price);
        } else {
            Util.cinform(AnsiColor.RED + "[Postal] Problem charging " + name(player) + " for distribution.");
        }
    }

    // ---- Postage escrow (docs/economy.md) -------------------------------------------------

    /**
     * What addressing {@code item} will hold: the out-of-town price for its kind, or 0 if postage is already
     * held for it (re-addressing is free) or there's no economy.
     */
    public static double postage_to_hold(ItemStack item, boolean parcel) {
        if (!VA_postal.economy_configured || active_hold(item) != null) {
            return 0.0D;
        }
        return parcel ? C_Economy.ship_price(false) : C_Economy.postage_price(false);
    }

    /** The postage hold for {@code item}, or null if it has none (or it was settled or expired). */
    public static Hold active_hold(ItemStack item) {
        return EconomyState.hold(HoldTag.read(item));
    }

    /**
     * Takes postage for newly addressed mail: the out-of-town price, held at Central until the mail is
     * delivered, when it's settled by the offices that actually handled it ({@link #settle_postage}). Mail
     * that already has postage held ({@code old_item}, when re-addressing) keeps its hold.
     *
     * @return {@code new_item} tagged with its hold, or null if the sender couldn't pay
     */
    public static ItemStack hold_postage(Player player, ItemStack old_item, ItemStack new_item, boolean parcel) {
        if (!VA_postal.economy_configured) {
            return new_item;
        }
        Hold existing = active_hold(old_item);
        if (existing != null) {
            Util.pinform(player, "&6There is no charge for re-addressing.");
            return HoldTag.write(new_item, existing.id);
        }
        double price = parcel ? C_Economy.ship_price(false) : C_Economy.postage_price(false);
        if (price <= 0.0D) {
            return HoldTag.write(new_item, null);
        }
        if (!withdraw_from_player(player, price)) {
            Util.pinform(player, "&f&oYou don't have enough money to cover " + (parcel ? "shipping." : "postage."));
            return null;
        }
        deposit_to_central(price);
        EconomyState.record(parcel ? EconomyState.Flow.SHIPPING : EconomyState.Flow.POSTAGE, price);
        Hold hold = new Hold(UUID.randomUUID().toString(), player.getUniqueId(), parcel, price, 0.0D, now(), null);
        EconomyState.put_hold(hold);
        Util.pinform(player, "&6Thank you for your payment. &7&o(Held until delivery; local mail gets the difference back.)");
        return HoldTag.write(new_item, hold.id);
    }

    /**
     * Takes a COD surcharge for {@code label}, held with its postage until delivery.
     *
     * @return the label tagged with its hold, or null if the sender couldn't pay
     */
    public static ItemStack hold_cod_surcharge(Player player, ItemStack label) {
        double price = C_Economy.cod_surchg();
        if (!VA_postal.economy_configured || price <= 0.0D) {
            return label;
        }
        if (!withdraw_from_player(player, price)) {
            Util.pinform(player, "&f&oYou don't have enough money to cover the COD surcharge.");
            return null;
        }
        deposit_to_central(price);
        EconomyState.record(EconomyState.Flow.COD_SURCHARGE, price);
        Hold hold = active_hold(label);
        if (hold == null) {
            hold = new Hold(UUID.randomUUID().toString(), player.getUniqueId(), true, 0.0D, price, now(), null);
            EconomyState.put_hold(hold);
        } else {
            hold.cod += price;
            EconomyState.touch_hold();
        }
        Util.pinform(player, "&6Thank you for your payment.");
        return HoldTag.write(label, hold.id);
    }

    /**
     * A postman is picking {@code item} up at {@code office}. The first office to pick mail up is its sending
     * office. Returns false if the mail's postage has expired (it was refunded): it stays where it is until
     * it's re-addressed.
     */
    public static boolean postage_collected(ItemStack item, String office) {
        String id = HoldTag.read(item);
        if (id == null || !VA_postal.economy_configured) {
            return true;
        }
        Hold hold = EconomyState.hold(id);
        if (hold == null) {
            Util.dinform("[Postal] Mail with expired postage left at " + Util.df(office) + "; it must be re-addressed.");
            return false;
        }
        if (hold.origin == null && office != null) {
            hold.origin = office.toLowerCase().trim();
            EconomyState.touch_hold();
        }
        return true;
    }

    /** True if {@code item} had postage held that expired (and was refunded) before it was picked up. */
    public static boolean postage_expired(ItemStack item) {
        return VA_postal.economy_configured && HoldTag.read(item) != null && active_hold(item) == null;
    }

    /**
     * {@code item} was delivered by {@code dest_office}: pays the offices their shares of the postage held for
     * it and refunds the sender whatever was held beyond the price of the route it actually took.
     */
    public static void settle_postage(ItemStack item, String dest_office) {
        if (!VA_postal.economy_configured || dest_office == null) {
            return;
        }
        String id = HoldTag.read(item);
        if (id == null) {
            id = com.vodhanel.minecraft.va_postal.mail.Letters.hold_of(item); // a book rebuilt from its record
        }
        Hold hold = EconomyState.remove_hold(id);
        if (hold == null) {
            return;
        }
        String origin = hold.origin == null ? dest_office : hold.origin;
        boolean local = origin.equalsIgnoreCase(dest_office);
        double price = hold.parcel ? C_Economy.ship_price(local) : C_Economy.postage_price(local);
        Postage.Split split = Postage.settle(hold.base, hold.cod, price, local);
        pay_office_from_central(origin, split.origin);
        if (!local) {
            pay_office_from_central(dest_office, split.dest);
        }
        if (split.refund > 0.005D) {
            refund_postage(hold.payer, split.refund, "&6Postal refunded " + ef(split.refund)
                    + " of your postage: your mail was delivered by its local office.");
        }
    }

    /** Refunds a hold in full and closes it: a parcel cancelled by its sender before it was posted. */
    public static void cancel_hold(String hold_id) {
        if (!VA_postal.economy_configured || hold_id == null) {
            return;
        }
        Hold hold = EconomyState.remove_hold(hold_id);
        if (hold != null) {
            refund_postage(hold.payer, hold.total(), "&6Postal refunded " + ef(hold.total())
                    + " of postage for the parcel you cancelled.");
        }
    }

    /** Refunds postage held for mail that was never picked up within the expiry. Run each Postal day. */
    public static int expire_holds() {
        if (!VA_postal.economy_configured) {
            return 0;
        }
        long cutoff = now() - (long) C_Economy.hold_expiry_days() * C_Economy.day_seconds();
        int expired = 0;
        for (Hold hold : EconomyState.holds()) {
            if (hold.origin == null && hold.created < cutoff) {
                EconomyState.remove_hold(hold.id);
                refund_postage(hold.payer, hold.total(), "&6Postal refunded " + ef(hold.total())
                        + " for mail you addressed but never posted. Re-address it to send it.");
                expired++;
            }
        }
        return expired;
    }

    private static void pay_office_from_central(String office, double amount) {
        if (amount <= 0.0D) {
            return;
        }
        if (withdraw_from_central(amount) && deposit_to_local(office, amount)) {
            EconomyState.add_revenue(office, amount);
        }
    }

    private static void refund_postage(UUID payer, double amount, String message) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(payer);
        create_player_account(player);
        if (withdraw_from_central(amount)) {
            if (deposit_to_player(player, amount)) {
                EconomyState.record(EconomyState.Flow.POSTAGE_REFUND, amount);
                Player online = player.getPlayer();
                if (online != null) {
                    Util.pinform(online, message);
                }
            } else {
                deposit_to_central(amount);
            }
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
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
            EconomyState.record(EconomyState.Flow.OFFICE_PURCHASE, price);
            // Settle the previous owner first (that empties the office down to its address escrow), then
            // seed the office with its floor and give Central the rest, which it holds for the refund.
            synchronize_bank_owner(player, dest_po, subject);
            double seed = com.vodhanel.minecraft.va_postal.economy.Reserves.office_seed(price, C_Economy.office_floor());
            if (!deposit_to_local(dest_po, seed)) {
                seed = 0.0D;
            }
            deposit_to_central(price - seed);
            Util.cinform("\033[0;32m[Postal] Seeded " + Util.df(dest_po) + " with " + ef(seed) + ", deposited " + ef(price - seed) + " to Central");
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
            P_Day.seed_server_office(stown);
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

        // The office keeps the escrow it holds for its player-owned addresses' refunds; the rest is settled.
        double escrow = P_Bank.office(stown).liability;
        double existing_balance = Math.max(0.0D, local_balance(stown) - escrow);
        if (existing_owner == null) {
            if (existing_balance > 0.0D && PostalEconomy.office_to_central(stown, existing_balance)) {
                EconomyState.record(EconomyState.Flow.SWEEP, existing_balance);
                Util.cinform("\033[0;32m[Postal] Balance of " + ef(existing_balance) + " moved to Central");
            }
            return;
        }

        OfflinePlayer previous = Bukkit.getOfflinePlayer(existing_owner);
        create_player_account(previous);
        if (existing_balance > 0.0D && withdraw_from_local(stown, existing_balance)) {
            if (deposit_to_player(previous, existing_balance)) {
                EconomyState.record(EconomyState.Flow.WITHDRAWAL, existing_balance);
                Util.cinform("\033[0;33m[Postal] Balance of " + ef(existing_balance) + " from " + Util.df(stown) + " paid to " + name(previous));
            } else {
                deposit_to_central(existing_balance);
                Util.cinform("\033[0;32m[Postal] Balance of " + ef(existing_balance) + " moved to Central");
            }
        }

        // Central's share of the price; the seed came back with the office balance above.
        double price = C_Economy.po_purchase_price()
                - com.vodhanel.minecraft.va_postal.economy.Reserves.office_seed(C_Economy.po_purchase_price(), C_Economy.office_floor());
        if (price > 0.0D) {
            if (withdraw_from_central(price)) {
                if (deposit_to_player(previous, price)) {
                    EconomyState.record(EconomyState.Flow.REFUND, price);
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
            EconomyState.record(EconomyState.Flow.ADDRESS_PURCHASE, price);
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
            EconomyState.record(EconomyState.Flow.REFUND, price);
            Util.cinform("\033[0;32m[Postal] Address price of " + ef(price) + " refunded to " + name(previous));
        } else {
            deposit_to_central(price - dist);
            deposit_to_local(stown, dist);
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

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
