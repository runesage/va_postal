package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;

public class P_Economy {
    VA_postal plugin;
    public static int last_central_dist = 0;

    public P_Economy(VA_postal instance) {
        this.plugin = instance;
    }

    public static void init_economy() {
        last_central_dist = Util.time_stamp();
    }

    public static void ping_economy_schedule() {
        if (VA_postal.economy_configured) {
            int daily_interval = 1200;
            int current_interval = Util.time_stamp() - last_central_dist;
            if (current_interval > daily_interval) {
                if (!does_central_exist()) {
                    create_central();
                }

                double retension = C_Economy.po_purchase_price();
                double central_balance = central_balance();
                if (central_balance <= retension) {
                    return;
                }

                String[] town_list = C_Arrays.town_list();
                if (town_list == null || town_list.length <= 0) {
                    return;
                }

                double sblit_bal = central_balance - retension;
                double po_share = sblit_bal / town_list.length;
                double transfered = 0.0;
                Util.cinform("\u001b[0;37m[Postal] ============================================");
                Util.cinform("\u001b[0;33m[Postal] Daily distribution of central proceeds......");
                Util.cinform("\u001b[0;33m[Postal] Beginning central balance ------ \u001b[0;37m" + fixed_len_rt(ef(central_balance), 10));
                Util.cinform("\u001b[0;33m[Postal] Local post office share -------- \u001b[0;37m" + fixed_len_rt(ef(po_share), 10));
                Util.cinform("\u001b[0;33m[Postal] New local balances:");

                for (String aTown_list : town_list) {
                    if (does_the_bank_exist(aTown_list) && deposit_to_local(aTown_list, po_share)) {
                        transfered += po_share;
                        String new_bal = fixed_len_rt(ef(local_balance(aTown_list)), 10);
                        String f_postoffice = fixed_len(df(aTown_list), 16, " ");
                        Util.cinform("\u001b[0;33m[Postal]    " + f_postoffice + "  " + "\u001b[0;37m" + new_bal);
                    }
                }

                withdraw_from_central(transfered);
                double remaining = central_balance - transfered;
                Util.cinform("\u001b[0;33m[Postal] Ending  central  balance  ------ \u001b[0;37m" + fixed_len_rt(ef(remaining), 10));
                Util.cinform("\u001b[0;37m[Postal] ============================================");
            }
        }
    }

    public static void create_bank(String sbank, String sowner) {
        if (!does_the_bank_exist(sbank)) {
            VA_postal.econ.createBank(df(sbank), sowner);
            Util.cinform("\u001b[0;33m[Postal] Created bank: " + df(sbank) + " owned by " + df(sowner));
        }
    }

    public static void create_central() {
        if (!does_the_bank_exist("Central")) {
            VA_postal.econ.createBank("Central", "Server");
            Util.cinform("\u001b[0;33m[Postal] Created Central bank");
        }
    }

    public static void delete_bank(String sbank) {
        if (does_the_bank_exist(sbank)) {
            VA_postal.econ.deleteBank(df(sbank));
        }
    }

    public static boolean does_the_bank_exist(String sbank) {
        for (String bank : VA_postal.econ.getBanks()) {
            if (bank.equalsIgnoreCase(sbank)) {
                return true;
            }
        }

        return false;
    }

    public static boolean does_central_exist() {
        for (String bank : VA_postal.econ.getBanks()) {
            if (bank.equalsIgnoreCase("Central")) {
                return true;
            }
        }

        return false;
    }

    public static boolean does_player_have_account(String splayer) {
        return VA_postal.econ.hasAccount(splayer);
    }

    public static boolean create_player_account(String splayer) {
        if (!VA_postal.econ.createPlayerAccount(splayer)) {
            Util.cinform("\u001b[1;31m[Postal] Problem creating account for " + df(splayer));
            return false;
        } else {
            return true;
        }
    }

    public static boolean is_the_bank_owner(String sbank, String splayer) {
        String sowner = "Server";
        if (C_Owner.is_local_po_owner_defined(sbank)) {
            sowner = C_Owner.get_owner_local_po(sbank);
            if (sowner.equalsIgnoreCase(splayer)) {
                return true;
            }
        } else if (splayer.equalsIgnoreCase("Server")) {
            return true;
        }

        return false;
    }

    public static String get_bank_owner(String sbank) {
        return C_Owner.is_local_po_owner_defined(sbank) ? C_Owner.get_owner_local_po(sbank) : "Server";
    }

    public static boolean does_central_have_amount(double amount) {
        EconomyResponse cen = VA_postal.econ.bankHas("Central", amount);
        return cen.transactionSuccess();
    }

    public static boolean does_local_have_amount(String local_po, double amount) {
        EconomyResponse loc = VA_postal.econ.bankHas(df(local_po), amount);
        return loc.transactionSuccess();
    }

    public static boolean does_player_have_amount(String splayer, double amount) {
        if (VA_postal.econ.hasAccount(splayer)) {
            if (VA_postal.econ.has(splayer, amount)) {
                return true;
            }
        } else {
            Util.cinform("\u001b[1;31m[Postal] Problem verifying player amount from " + df(splayer));
        }

        return false;
    }

    public static double central_balance() {
        double result = 0.0;
        EconomyResponse cen = VA_postal.econ.bankBalance("Central");
        if (!cen.transactionSuccess()) {
            Util.cinform("\u001b[1;31m[Postal] Problem obtaining Central balance");
        } else {
            result = cen.balance;
        }

        return result;
    }

    public static double local_balance(String local_po) {
        double result = 0.0;
        EconomyResponse loc = VA_postal.econ.bankBalance(df(local_po));
        if (!loc.transactionSuccess()) {
            Util.cinform("\u001b[1;31m[Postal] Problem obtaining balance from " + df(local_po));
        } else {
            result = loc.balance;
        }

        return result;
    }

    public static double player_balance(String splayer) {
        if (VA_postal.econ.hasAccount(splayer)) {
            return VA_postal.econ.getBalance(splayer);
        }

        Util.cinform("\u001b[1;31m[Postal] Problem obtaining player balance from " + df(splayer));
        return 0.0;
    }

    public static boolean deposit_to_central(double amount) {
        EconomyResponse cen = VA_postal.econ.bankDeposit("Central", amount);
        if (!cen.transactionSuccess()) {
            Util.cinform("\u001b[1;31m[Postal] Problem depositing to Central");
            return false;
        } else {
            return true;
        }
    }

    public static boolean deposit_to_local(String local_po, double amount) {
        EconomyResponse loc = VA_postal.econ.bankDeposit(df(local_po), amount);
        if (!loc.transactionSuccess()) {
            Util.cinform("\u001b[1;31m[Postal] Problem depositing to " + df(local_po));
            return false;
        } else {
            return true;
        }
    }

    public static boolean deposit_to_player(String splayer, double amount) {
        EconomyResponse ply = VA_postal.econ.depositPlayer(splayer, amount);
        if (!ply.transactionSuccess()) {
            Util.cinform("\u001b[1;31m[Postal] Problem depositing to " + df(splayer));
            return false;
        } else {
            return true;
        }
    }

    public static boolean withdraw_from_central(double amount) {
        EconomyResponse cen = VA_postal.econ.bankWithdraw("Central", amount);
        if (!cen.transactionSuccess()) {
            Util.cinform("\u001b[1;31m[Postal] Problem depositing to Central");
            return false;
        } else {
            return true;
        }
    }

    public static boolean withdraw_from_local(String local_po, double amount) {
        EconomyResponse loc = VA_postal.econ.bankWithdraw(df(local_po), amount);
        if (!loc.transactionSuccess()) {
            Util.cinform("\u001b[1;31m[Postal] Problem depositing to " + df(local_po));
            return false;
        } else {
            return true;
        }
    }

    public static boolean withdraw_from_player(String splayer, double amount) {
        EconomyResponse ply = VA_postal.econ.withdrawPlayer(splayer, amount);
        if (!ply.transactionSuccess()) {
            Util.cinform("\u001b[1;31m[Postal] Problem withdrawing from " + df(splayer));
            return false;
        } else {
            return true;
        }
    }

    public static void verify_bank(String stown) {
        if (VA_postal.economy_configured) {
            String sowner = "Server";
            if (C_Owner.is_local_po_owner_defined(stown)) {
                sowner = C_Owner.get_owner_local_po(stown);
            }

            if (!does_the_bank_exist(stown)) {
                create_bank(stown, sowner);
                if (!sowner.equalsIgnoreCase("Server")) {
                    double price = C_Economy.po_purchase_price();
                    deposit_to_central(price);
                }
            } else {
                synchronize_bank_owner(null, stown, sowner);
            }
        }
    }

    public static void verify_central() {
        if (VA_postal.economy_configured && !does_central_exist()) {
            create_central();
        }
    }

    public static double has_price_of_postage(Player player, String dest_po) {
        if (VA_postal.economy_configured) {
            String loc_po = get_local(player);
            boolean local = false;
            if (loc_po.equalsIgnoreCase(dest_po)) {
                local = true;
            }

            String splayer = player.getName().trim();
            if (does_player_have_account(splayer)) {
                double price = C_Economy.postage_price(local);
                if (does_player_have_amount(splayer, price)) {
                    return price;
                }
            }

            return -1.0;
        } else {
            return 0.0;
        }
    }

    public static double has_price_of_shipping(Player player, String dest_po) {
        if (VA_postal.economy_configured) {
            String loc_po = get_local(player);
            boolean local = false;
            if (loc_po.equalsIgnoreCase(dest_po)) {
                local = true;
            }

            String splayer = player.getName().trim();
            if (does_player_have_account(splayer)) {
                double price = C_Economy.ship_price(local);
                if (does_player_have_amount(splayer, price)) {
                    return price;
                }
            }

            return -1.0;
        } else {
            return 0.0;
        }
    }

    public static double has_price_of_cod(Player player) {
        if (VA_postal.economy_configured) {
            String splayer = player.getName().trim();
            if (does_player_have_account(splayer)) {
                double price = C_Economy.cod_surchg();
                if (does_player_have_amount(splayer, price)) {
                    return price;
                }
            }

            return -1.0;
        } else {
            return 0.0;
        }
    }

    public static double has_price_of_distr(Player player, String modifier, String stown) {
        if (VA_postal.economy_configured) {
            int dist_count = dist_count(modifier, stown);
            if (dist_count == -1) {
                return -10.0;
            }

            String splayer = player.getName().trim();
            if (does_player_have_account(splayer)) {
                double piece = C_Economy.distr_price();
                double price = piece * dist_count;
                if (does_player_have_amount(splayer, price)) {
                    return price;
                }
            }

            return -1.0;
        } else {
            return 0.0;
        }
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
            String[] addr_list = C_Arrays.addresses_list(stown);
            if (addr_list != null) {
                for (String saddress : addr_list) {
                    String sowner = "server";
                    if (("all_addresses".equalsIgnoreCase(modifier) || C_Owner.is_address_owner_defined(stown, saddress))
                        && ("all_towns".equalsIgnoreCase(srch_stown) || stown.equalsIgnoreCase(srch_stown))) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    public static void charge_distr(Player player, String modifier, String stown) {
        if (VA_postal.economy_configured) {
            String splayer = player.getName().trim();
            if (does_player_have_account(splayer)) {
                int dist_count = dist_count(modifier, stown);
                if (dist_count == -1) {
                    return;
                }

                double piece = C_Economy.distr_price();
                double price = piece * dist_count;
                if (withdraw_from_player(splayer, price)) {
                    Util.pinform(player, "&6Thank you for your payment.");
                    deposit_to_central(price);
                } else {
                    Util.cinform("\u001b[1;31m[Postal] Problem charging " + splayer + " for postage.");
                }
            }
        }
    }

    public static boolean can_central_buy_po() {
        double price = C_Economy.po_purchase_price();
        return central_balance() > price;
    }

    public static boolean can_central_buy_addr() {
        double price = C_Economy.addr_purchase_price();
        return central_balance() > price;
    }

    public static double has_price_of_postoffice(String splayer) {
        if (VA_postal.economy_configured) {
            if (does_player_have_account(splayer)) {
                double price = C_Economy.po_purchase_price();
                if (does_player_have_amount(splayer, price)) {
                    return price;
                }
            }

            return -1.0;
        } else {
            return 0.0;
        }
    }

    public static double charge_po_purchase(Player player, String subject, String dest_po) {
        if (subject.equalsIgnoreCase("none")) {
            subject = "Server";
            synchronize_bank_owner(player, dest_po, subject);
            return 0.0;
        }

        if (C_Owner.is_local_po_owner_defined(dest_po) && subject.equalsIgnoreCase(C_Owner.get_owner_local_po(dest_po))) {
            synchronize_bank_owner(player, dest_po, subject);
            if (player == null) {
                Util.cinform("[Postal] player " + df(subject) + " already owns post office " + df(dest_po));
            } else {
                Util.pinform(player, "[Postal] player " + df(subject) + " already owns post office " + df(dest_po));
            }

            return 0.0;
        } else {
            if (does_player_have_account(subject)) {
                double price = C_Economy.po_purchase_price();
                if (withdraw_from_player(subject, price)) {
                    Util.cinform("\u001b[0;33m[Postal] Withdrawn " + ef(price) + " from player " + df(subject));
                    deposit_to_central(price);
                    Util.cinform("\u001b[0;32m[Postal] Deposited " + ef(price) + " to Central bank");
                    synchronize_bank_owner(player, dest_po, subject);
                    return price;
                }

                Util.cinform("\u001b[1;31m[Postal] Problem charging " + subject + " for PO purchase.");
            }

            return 0.0;
        }
    }

    public static void synchronize_bank_owner(Player player, String stown, String sowner) {
        if (stown != null && sowner != null) {
            String existing_owner = "Server";
            if (C_Owner.is_local_po_owner_defined(stown)) {
                existing_owner = C_Owner.get_owner_local_po(stown);
            }

            if (existing_owner.equalsIgnoreCase(sowner)) {
                sync_econ_bank_owner(stown, sowner);
            } else if (sowner.equalsIgnoreCase("Server")) {
                sync_econ_bank_owner(stown, "Server");
                C_Owner.del_owner_local_po(stown);
                if (player == null) {
                    Util.con_type("Owner removed from:  " + df(stown));
                } else {
                    Util.pinform(player, "Owner removed from:  " + df(stown));
                }
            } else {
                if (C_Owner.is_local_po_owner_defined(stown)) {
                    if (!is_the_bank_owner(stown, sowner)) {
                        sync_econ_bank_owner(stown, sowner);
                        C_Owner.set_owner_local_po(stown, sowner);
                        if (player == null) {
                            Util.con_type(df(stown) + " is now owned by " + sowner);
                        } else {
                            Util.pinform(player, df(stown) + " is now owned by " + sowner);
                        }
                    } else {
                        sync_econ_bank_owner(stown, sowner);
                    }
                } else {
                    sync_econ_bank_owner(stown, sowner);
                    C_Owner.set_owner_local_po(stown, sowner);
                    if (player == null) {
                        Util.con_type(df(stown) + " is now owned by " + sowner);
                    } else {
                        Util.pinform(player, df(stown) + " is now owned by " + sowner);
                    }
                }
            }
        }
    }

    public static void sync_econ_bank_owner(String stown, String sowner) {
        if (does_the_bank_exist(stown)) {
            if (!is_the_bank_owner(stown, sowner)) {
                String existing_owner = "Server";
                if (C_Owner.is_local_po_owner_defined(stown)) {
                    existing_owner = C_Owner.get_owner_local_po(stown);
                }

                double existing_balance = 0.0;
                existing_balance = local_balance(stown);
                if (!existing_owner.equalsIgnoreCase("Server")) {
                    double price = C_Economy.po_purchase_price();
                    if (!does_player_have_account(existing_owner)) {
                        create_player_account(existing_owner);
                    }

                    if (!deposit_to_player(existing_owner, existing_balance + price)) {
                        if (!does_central_exist()) {
                            create_central();
                        }

                        if (existing_balance > 0.0) {
                            deposit_to_central(existing_balance);
                        }

                        Util.cinform("\u001b[0;32m[Postal] Balance of " + ef(existing_balance) + " moved to Central bank for distribution");
                    } else {
                        withdraw_from_central(price);
                        if (existing_balance > 0.0) {
                            Util.cinform("\u001b[0;33m[Postal] Withdrawn " + ef(existing_balance) + " from local post office " + df(stown));
                        }

                        Util.cinform("\u001b[0;33m[Postal] Withdrawn " + ef(price) + " from Central bank");
                        Util.cinform("\u001b[0;32m[Postal] Price and balance of " + ef(existing_balance + price) + " returned to player " + df(existing_owner));
                    }
                } else {
                    if (!does_central_exist()) {
                        create_central();
                    }

                    if (existing_balance > 0.0) {
                        deposit_to_central(existing_balance);
                        Util.cinform("\u001b[0;32m[Postal] Balance of " + ef(existing_balance) + " moved to Central bank for distribution");
                    }
                }

                delete_bank(stown);
                create_bank(stown, sowner);
            }
        } else {
            create_bank(stown, sowner);
        }
    }

    public static double has_price_of_address(String splayer) {
        if (VA_postal.economy_configured) {
            if (does_player_have_account(splayer)) {
                double price = C_Economy.addr_purchase_price();
                if (does_player_have_amount(splayer, price)) {
                    return price;
                }
            }

            return -1.0;
        } else {
            return 0.0;
        }
    }

    public static double charge_addr_purchase(Player player, String subject, String dest_po, String dest_addr) {
        if (subject.equalsIgnoreCase("none")) {
            subject = "Server";
            synchronize_addr_owner(player, dest_po, dest_addr, subject);
            return 0.0;
        }

        if (C_Owner.is_address_owner_defined(dest_po, dest_addr) && subject.equalsIgnoreCase(C_Owner.get_owner_address(dest_po, dest_addr))) {
            synchronize_addr_owner(player, dest_po, dest_addr, subject);
            if (player == null) {
                Util.cinform("[Postal] player " + df(subject) + " already owns " + df(dest_po) + ", " + df(dest_addr));
            } else {
                Util.pinform(player, "[Postal] player " + df(subject) + " already owns " + df(dest_po) + ", " + df(dest_addr));
            }

            return 0.0;
        } else {
            if (does_player_have_account(subject)) {
                double price = C_Economy.addr_purchase_price();
                if (withdraw_from_player(subject, price)) {
                    Util.cinform("\u001b[0;33m[Postal] Withdrawn " + ef(price) + " from player " + df(subject));
                    double dist = price / 2.0;
                    deposit_to_central(dist);
                    Util.cinform("\u001b[0;32m[Postal] Deposited " + ef(dist) + " to Central bank");
                    deposit_to_local(dest_po, dist);
                    Util.cinform("\u001b[0;32m[Postal] Deposited " + ef(dist) + " to local " + df(dest_po));
                    synchronize_addr_owner(player, dest_po, dest_addr, subject);
                    return price;
                }

                Util.cinform("\u001b[1;31m[Postal] Problem charging " + subject + " for address purchase.");
            }

            return 0.0;
        }
    }

    public static void synchronize_addr_owner(Player player, String stown, String saddr, String sowner) {
        if (stown != null && saddr != null && sowner != null) {
            if (sowner.equalsIgnoreCase("none")) {
                sowner = "Server";
            }

            String existing_owner = "Server";
            if (C_Owner.is_address_owner_defined(stown, saddr)) {
                existing_owner = C_Owner.get_owner_address(stown, saddr);
            }

            if (existing_owner.equalsIgnoreCase(sowner)) {
                sync_econ_addr_owner(stown, saddr, sowner);
            } else if (sowner.equalsIgnoreCase("Server")) {
                sync_econ_addr_owner(stown, saddr, "Server");
                C_Owner.del_owner_address(stown, saddr);
                if (player == null) {
                    Util.con_type("Owner removed from:  " + df(stown) + ", " + df(saddr));
                } else {
                    Util.pinform(player, "Owner removed from:  " + df(stown) + ", " + df(saddr));
                }
            } else {
                if (C_Owner.is_address_owner_defined(stown, saddr)) {
                    sync_econ_addr_owner(stown, saddr, sowner);
                    C_Owner.set_owner_address(stown, saddr, sowner);
                    if (player == null) {
                        Util.con_type(df(stown) + ", " + df(saddr) + " now owned by " + df(sowner));
                    } else {
                        Util.pinform(player, df(stown) + ", " + df(saddr) + " now owned by " + df(sowner));
                    }
                } else {
                    sync_econ_addr_owner(stown, saddr, sowner);
                    C_Owner.set_owner_address(stown, saddr, sowner);
                    if (player == null) {
                        Util.con_type(df(stown) + ", " + df(saddr) + " now owned by " + df(sowner));
                    } else {
                        Util.pinform(player, df(stown) + ", " + df(saddr) + " now owned by " + df(sowner));
                    }
                }
            }
        }
    }

    public static void sync_econ_addr_owner(String stown, String saddr, String sowner) {
        String cur_addr_owner = "Server";
        if (C_Owner.is_address_owner_defined(stown, saddr)) {
            cur_addr_owner = C_Owner.get_owner_address(stown, saddr);
        }

        if (!cur_addr_owner.equalsIgnoreCase(sowner)) {
            double price = C_Economy.addr_purchase_price();
            if (!cur_addr_owner.equalsIgnoreCase("Server")) {
                if (!does_player_have_account(cur_addr_owner)) {
                    create_player_account(cur_addr_owner);
                }

                if (deposit_to_player(cur_addr_owner, price)) {
                    double dist = price / 2.0;
                    withdraw_from_central(dist);
                    Util.cinform("\u001b[0;33m[Postal] Withdrawn " + ef(dist) + " from Central bank");
                    withdraw_from_local(stown, dist);
                    Util.cinform("\u001b[0;33m[Postal] Withdrawn " + ef(dist) + " from local " + df(stown));
                    Util.cinform("\u001b[0;32m[Postal] Balance of " + ef(price) + " returned to player " + df(cur_addr_owner));
                }
            }
        }
    }

    public static void charge_postage(Player player, String dest_po) {
        if (VA_postal.economy_configured) {
            String splayer = player.getName().trim();
            boolean local = false;
            String loc_po = get_local(player);
            if (loc_po == null) {
                loc_po = dest_po;
            }

            if (does_player_have_account(splayer)) {
                double price = 0.0;
                if (loc_po.equalsIgnoreCase(dest_po)) {
                    local = true;
                    price = C_Economy.postage_price(true);
                } else {
                    price = C_Economy.postage_price(false);
                }

                if (withdraw_from_player(splayer, price)) {
                    Util.pinform(player, "&6Thank you for your payment.");
                    if (local) {
                        double dist = price / 2.0;
                        deposit_to_central(dist);
                        deposit_to_local(df(loc_po), dist);
                    } else {
                        double dist = price / 3.0;
                        deposit_to_central(dist);
                        deposit_to_local(df(loc_po), dist);
                        deposit_to_local(df(dest_po), dist);
                    }
                } else {
                    Util.cinform("\u001b[1;31m[Postal] Problem charging " + splayer + " for postage.");
                }
            }
        }
    }

    public static void charge_shipping(Player player, String dest_po) {
        if (VA_postal.economy_configured) {
            String splayer = player.getName().trim();
            boolean local = false;
            String loc_po = get_local(player);
            if (loc_po == null) {
                loc_po = dest_po;
            }

            if (does_player_have_account(splayer)) {
                double price = 0.0;
                if (loc_po.equalsIgnoreCase(dest_po)) {
                    local = true;
                    price = C_Economy.ship_price(true);
                } else {
                    price = C_Economy.ship_price(false);
                }

                if (withdraw_from_player(splayer, price)) {
                    Util.pinform(player, "&6Thank you for your payment.");
                    if (local) {
                        double dist = price / 2.0;
                        deposit_to_central(dist);
                        deposit_to_local(df(loc_po), dist);
                    } else {
                        double dist = price / 3.0;
                        deposit_to_central(dist);
                        deposit_to_local(df(loc_po), dist);
                        deposit_to_local(df(dest_po), dist);
                    }
                }
            }
        }
    }

    public static void charge_player(Player player, double amount) {
        if (VA_postal.economy_configured) {
            String splayer = player.getName().trim();
            if (does_player_have_account(splayer) && withdraw_from_player(splayer, amount)) {
                Util.pinform(player, "&6Thank you for your payment.");
            }
        }
    }

    public static boolean pay_player(String splayer, double amount) {
        if (VA_postal.economy_configured) {
            if (!does_player_have_account(splayer)) {
                create_player_account(splayer);
            }

            if (deposit_to_player(splayer, amount)) {
                return true;
            }
        }

        return false;
    }

    public static void charge_cod_surcharge(Player player) {
        if (VA_postal.economy_configured) {
            String splayer = player.getName().trim();
            if (does_player_have_account(splayer)) {
                double price = C_Economy.cod_surchg();
                String loc_po = get_local(player);
                if (withdraw_from_player(splayer, price)) {
                    Util.pinform(player, "&6Thank you for your payment.");
                    double dist = price / 2.0;
                    deposit_to_central(dist);
                    deposit_to_local(df(loc_po), dist);
                }
            }
        }
    }

    public static String get_local(Player player) {
        String[] list = C_Arrays.geo_po_list_sorted(player);
        String loc_po = "";
        if (list != null && list.length > 1) {
            String[] parts = list[0].split(",");
            if (parts != null) {
                return parts[1].trim();
            }

            Util.cinform("\u001b[1;31m[Postal] Problem finding local PO geo list to calculate postage. ");
        } else {
            Util.cinform("\u001b[1;31m[Postal] Problem splitting local PO geo list to calculate postage. ");
        }

        return null;
    }

    public static String ef(double value) {
        return VA_postal.economy_configured ? VA_postal.econ.format(value) : "-1";
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

    public static String df(String string) {
        try {
            String[] parts = string.split("_");
            String name = proper(parts[0]);
            if (parts.length > 1) {
                name = name + "_" + Util.proper(parts[1]);
            }

            if (parts.length > 2) {
                name = name + "_" + Util.proper(parts[2]);
            }

            if (parts.length > 3) {
                name = name + "_" + Util.proper(parts[3]);
            }

            return name;
        } catch (Exception e) {
            return "";
        }
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
