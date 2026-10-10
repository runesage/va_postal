package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.economy.EconomyState;
import com.vodhanel.minecraft.va_postal.economy.PostalDay;
import com.vodhanel.minecraft.va_postal.economy.Reserves;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

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

        /** Unpaid upkeep. While any is owed the owner can't withdraw. */
        public double arrears() {
            return EconomyState.arrears(name);
        }

        /** What the owner may take out now: 0 for server-owned offices (their surplus goes to Central) or in arrears. */
        public double withdrawable() {
            return owner == null || arrears() > 0.0D ? 0.0D : Reserves.withdrawable(balance, reserve);
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

    /**
     * Central's refunds: office prices of player-owned offices plus its half of player-owned addresses, plus
     * all postage held in escrow for mail not yet delivered.
     */
    public static double central_liability() {
        int owned_offices = 0;
        int owned_addresses = 0;
        for (String office : offices()) {
            if (C_Owner.get_owner_local_po_id(office) != null) {
                owned_offices++;
            }
            owned_addresses += player_owned_addresses(office);
        }
        return Reserves.central_liability(owned_offices, C_Economy.po_purchase_price(), C_Economy.office_floor(),
                owned_addresses, C_Economy.addr_purchase_price()) + EconomyState.held_total();
    }

    public static double central_target() {
        return Reserves.central_target(central_liability(), C_Economy.central_buffer());
    }

    /** Applies Economy.Central_account: Postal's own Central, or (opt-in) Towny's server account. */
    public static void choose_central_account() {
        com.vodhanel.minecraft.va_postal.economy.PostalEconomy.use_postal_central();
        if (!"towny".equals(C_Economy.central_account())) {
            return;
        }
        String problem;
        if (Bukkit.getPluginManager().getPlugin("Towny") == null || !Bukkit.getPluginManager().isPluginEnabled("Towny")) {
            problem = "Towny is not installed";
        } else {
            try {
                problem = P_TownyTreasury.use_towny_server_account();
            } catch (LinkageError | RuntimeException e) {
                problem = "Towny's API is not available (" + e + ")";
            }
        }
        if (problem != null) {
            Util.cinform(AnsiColor.RED + "[Postal] Economy.Central_account is towny, but " + problem
                    + "; using postal-central.");
        }
    }

    // ---- Owner transfers -------------------------------------------------------------------

    /** Why a deposit or withdrawal didn't happen (or {@code DONE}). */
    public enum Result { DONE, NOT_OWNER, NO_ACCOUNT, OVER_RESERVE, INSUFFICIENT_FUNDS, ECONOMY_ERROR }

    /**
     * Moves {@code amount} from the office to its owner, down to the reserve. Both legs are checked, and
     * the first is undone if the second fails, so money is never lost or created.
     */
    public static Result withdraw(String office, OfflinePlayer owner, double amount) {
        if (owner == null || !owner.getUniqueId().equals(C_Owner.get_owner_local_po_id(office))) {
            return Result.NOT_OWNER;
        }
        if (!P_Economy.does_the_bank_exist(office)) {
            return Result.NO_ACCOUNT;
        }
        if (amount > office(office).withdrawable() + 1e-9) {
            return Result.OVER_RESERVE;
        }
        if (!P_Economy.withdraw_from_local(office, amount)) {
            return Result.ECONOMY_ERROR;
        }
        P_Economy.create_player_account(owner);
        if (!P_Economy.deposit_to_player(owner, amount)) {
            P_Economy.deposit_to_local(office, amount);
            return Result.ECONOMY_ERROR;
        }
        EconomyState.record(EconomyState.Flow.WITHDRAWAL, amount);
        return Result.DONE;
    }

    /** Moves {@code amount} from the owner into the office, e.g. to restore its reserve. */
    public static Result deposit(String office, OfflinePlayer owner, double amount) {
        if (owner == null || !owner.getUniqueId().equals(C_Owner.get_owner_local_po_id(office))) {
            return Result.NOT_OWNER;
        }
        if (!P_Economy.does_the_bank_exist(office)) {
            P_Economy.create_bank(office);
        }
        if (!P_Economy.does_player_have_amount(owner, amount)) {
            return Result.INSUFFICIENT_FUNDS;
        }
        if (!P_Economy.withdraw_from_player(owner, amount)) {
            return Result.ECONOMY_ERROR;
        }
        if (!P_Economy.deposit_to_local(office, amount)) {
            P_Economy.deposit_to_player(owner, amount);
            return Result.ECONOMY_ERROR;
        }
        EconomyState.record(EconomyState.Flow.DEPOSIT, amount);
        P_Day.settle_arrears(office);
        return Result.DONE;
    }

    // ---- /postal office --------------------------------------------------------------------

    private static final String OFFICE_USAGE = "&7Usage: /postal office [office] [balance | deposit <amount> | withdraw <amount|all>]";

    /**
     * {@code /postal office ...}: owners see and move their offices' money; {@code admin} (or the console)
     * may view any office.
     */
    public static void office_command(CommandSender sender, String[] args, boolean admin) {
        if (!VA_postal.economy_configured) {
            send(sender, "&7Economy is not enabled (Economy.Use in config.yml).");
            return;
        }
        Player player = sender instanceof Player ? (Player) sender : null;
        if (args.length < 2) {
            list_owned(sender, player);
            return;
        }
        String office = com.vodhanel.minecraft.va_postal.config.C_Postoffice.town_complete(args[1]);
        if ("null".equals(office)) {
            send(sender, "&7No post office matches '" + args[1] + "'. See /tlist.");
            return;
        }
        UUID owner = C_Owner.get_owner_local_po_id(office);
        boolean is_owner = player != null && player.getUniqueId().equals(owner);
        String action = args.length > 2 ? args[2].toLowerCase() : "balance";

        if ("balance".equals(action)) {
            if (!is_owner && !admin) {
                send(sender, "&7You don't own " + Util.df(office) + ".");
                return;
            }
            describe(sender, office(office));
            return;
        }
        if (!"deposit".equals(action) && !"withdraw".equals(action)) {
            send(sender, OFFICE_USAGE);
            return;
        }
        if (player == null) {
            send(sender, "&7Only the office's owner can move its money, in game.");
            return;
        }
        if (!is_owner) {
            send(sender, "&7Only the owner of " + Util.df(office) + " can do that.");
            return;
        }
        if (args.length < 4) {
            send(sender, OFFICE_USAGE);
            return;
        }
        double amount;
        if ("withdraw".equals(action) && "all".equalsIgnoreCase(args[3])) {
            amount = office(office).withdrawable();
        } else {
            amount = parse_amount(args[3]);
        }
        if (amount <= 0.0D) {
            String why = ". Use a positive amount.";
            if ("all".equalsIgnoreCase(args[3])) {
                double arrears = office(office).arrears();
                why = arrears > 0.0D ? ": " + Util.df(office) + " owes " + money(arrears) + " in upkeep, which must be paid first."
                        : ": the office is at its reserve of " + money(office(office).reserve) + ".";
            }
            send(sender, "&7Nothing to " + action + why);
            return;
        }
        Result result = "withdraw".equals(action) ? withdraw(office, player, amount) : deposit(office, player, amount);
        switch (result) {
            case DONE:
                send(sender, "&6" + ("withdraw".equals(action) ? "Withdrew " : "Deposited ") + money(amount)
                        + ("withdraw".equals(action) ? " from " : " into ") + Util.df(office) + ".");
                describe(sender, office(office));
                break;
            case OVER_RESERVE:
                send(sender, "&7" + Util.df(office) + " must keep its reserve of " + money(office(office).reserve)
                        + ". You can withdraw up to " + money(office(office).withdrawable()) + ".");
                break;
            case INSUFFICIENT_FUNDS:
                send(sender, "&7You don't have " + money(amount) + ".");
                break;
            case NO_ACCOUNT:
                send(sender, "&7" + Util.df(office) + " has no account yet.");
                break;
            default:
                send(sender, "&cThe economy refused the transfer; nothing was moved. See the server log.");
        }
    }

    private static void list_owned(CommandSender sender, Player player) {
        if (player == null) {
            send(sender, OFFICE_USAGE + " (or /postal bank for all offices)");
            return;
        }
        boolean any = false;
        for (String name : offices()) {
            if (player.getUniqueId().equals(C_Owner.get_owner_local_po_id(name))) {
                describe(sender, office(name));
                any = true;
            }
        }
        if (!any) {
            send(sender, "&7You don't own a post office.");
        }
        send(sender, OFFICE_USAGE);
    }

    private static void describe(CommandSender sender, Office o) {
        send(sender, "&e" + Util.df(o.name) + "&7: balance &f" + money(o.balance) + "&7, reserve " + money(o.reserve)
                + " (owes " + money(o.liability) + " for " + o.player_owned_addresses + " player-owned address"
                + (o.player_owned_addresses == 1 ? "" : "es") + " + floor " + money(C_Economy.office_floor()) + ")"
                + (o.owner == null ? "" : ", withdrawable &f" + money(o.withdrawable()))
                + (o.owner != null && o.balance < o.reserve ? " &c(below reserve: withdrawals blocked)" : "")
                + (o.arrears() > 0.0D ? " &c(upkeep arrears " + money(o.arrears()) + ": withdrawals blocked)" : ""));
    }

    /** A positive amount rounded to cents, or 0 if {@code text} isn't one. */
    static double parse_amount(String text) {
        try {
            double value = Double.parseDouble(text.replace(",", "").replace("$", "").trim());
            if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0D) {
                return 0.0D;
            }
            return Math.round(value * 100.0D) / 100.0D;
        } catch (NumberFormatException e) {
            return 0.0D;
        }
    }

    // ---- /postal bank ----------------------------------------------------------------------

    /** {@code /postal bank [newday]} for admins, from a player or the console. */
    public static void command(CommandSender sender, String[] args) {
        if (!VA_postal.economy_configured) {
            send(sender, "&7Economy is not enabled (Economy.Use in config.yml).");
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "":
                report(sender);
                return;
            case "newday":
                send(sender, "&7Running a Postal day now.");
                PostalDay.run_now();
                return;
            case "report":
                flow_report(sender, args.length > 2 ? Math.max(1, (int) parse_amount(args[2])) : 7);
                return;
            case "policy":
                policy(sender, args);
                return;
            default:
                send(sender, "&7Usage: /postal bank [newday | report [days] | policy [<setting> <value>]]");
        }
    }

    public static void report(CommandSender sender) {
        double central_balance = P_Economy.central_balance();
        double central_liability = central_liability();
        send(sender, "&6[Postal] Bank &7(next Postal day " + next_day() + ")");
        send(sender, "&eCentral &7balance &f" + money(central_balance) + " &7owes &f" + money(central_liability)
                + " &7target &f" + money(Reserves.central_target(central_liability, C_Economy.central_buffer()))
                + (central_balance < central_liability ? " &c(below what it owes)" : ""));
        int holds = EconomyState.holds().size();
        if (holds > 0) {
            send(sender, "&7  Postage held in escrow: &f" + money(EconomyState.held_total()) + " &7for " + holds
                    + " piece" + (holds == 1 ? "" : "s") + " of mail not yet delivered (included in what Central owes)");
        }
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
            if (o.arrears() > 0.0D) {
                flag += " &c(arrears " + money(o.arrears()) + ")";
            }
            send(sender, "&e" + Util.df(o.name) + "&7: " + owner + " | &f" + money(o.balance) + "&7 | "
                    + money(o.reserve) + " (" + money(o.liability) + " + " + money(C_Economy.office_floor()) + ") | &f"
                    + withdrawable + flag);
        }
    }

    // ---- /postal bank report ---------------------------------------------------------------

    private static void flow_report(CommandSender sender, int days) {
        send(sender, "&6[Postal] Flows &7(in: paid by players; out: paid to players; internal: between Postal accounts)");
        send(sender, "&eToday so far&7: " + flow_line(new java.util.LinkedHashMap<>(EconomyState.flows_today())));
        List<java.util.Map<String, Object>> history = EconomyState.history();
        if (history.isEmpty()) {
            send(sender, "&7No Postal days closed yet.");
        }
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");
        for (int i = 0; i < Math.min(days, history.size()); i++) {
            java.util.Map<String, Object> day = history.get(i);
            java.util.Map<EconomyState.Flow, Double> flows = new java.util.LinkedHashMap<>();
            for (EconomyState.Flow f : EconomyState.Flow.values()) {
                Object v = day.get(f.key());
                if (v instanceof Number) {
                    flows.put(f, ((Number) v).doubleValue());
                }
            }
            String closed = java.time.Instant.ofEpochSecond(number(day.get("closed")).longValue())
                    .atZone(java.time.ZoneId.systemDefault()).format(fmt);
            send(sender, "&eDay to " + closed + "&7: " + flow_line(flows));
            send(sender, "&7   Central " + money(num(day.get("central_end"))) + " (owes " + money(num(day.get("central_owes")))
                    + ", target " + money(num(day.get("central_target"))) + "), dividend pool " + money(num(day.get("pool")))
                    + ", carry " + money(num(day.get("carry"))) + ", arrears " + money(num(day.get("arrears"))));
        }
    }

    private static String flow_line(java.util.Map<EconomyState.Flow, Double> flows) {
        double in = 0, out = 0;
        StringBuilder internal = new StringBuilder();
        StringBuilder detail = new StringBuilder();
        for (java.util.Map.Entry<EconomyState.Flow, Double> e : flows.entrySet()) {
            double v = e.getValue();
            switch (e.getKey().direction) {
                case "in":
                    in += v;
                    detail.append(" ").append(e.getKey().key()).append(" ").append(money(v));
                    break;
                case "out":
                    out += v;
                    detail.append(" ").append(e.getKey().key()).append(" ").append(money(v));
                    break;
                default:
                    internal.append(" ").append(e.getKey().key()).append(" ").append(money(v));
            }
        }
        return "in &f" + money(in) + "&7, out &f" + money(out) + "&7, net &f" + money(in - out) + "&7 |"
                + (detail.length() == 0 ? " no player payments" : detail.toString())
                + (internal.length() == 0 ? "" : " | internal:" + internal);
    }

    private static double num(Object o) {
        return number(o).doubleValue();
    }

    private static Number number(Object o) {
        return o instanceof Number ? (Number) o : Double.valueOf(0.0D);
    }

    // ---- /postal bank policy ---------------------------------------------------------------

    /** A runtime-adjustable setting: its config path and allowed range (or choices). */
    private static final class Setting {
        final String path;
        final double min;
        final double max;
        final String[] choices;

        Setting(String path, double min, double max) {
            this.path = path;
            this.min = min;
            this.max = max;
            this.choices = null;
        }

        Setting(String path, String... choices) {
            this.path = path;
            this.min = 0;
            this.max = 0;
            this.choices = choices;
        }
    }

    private static final java.util.Map<String, Setting> SETTINGS = new java.util.LinkedHashMap<>();

    static {
        SETTINGS.put("dividend.basis", new Setting("economy.dividend.basis", "revenue", "deliveries"));
        SETTINGS.put("dividend.release_rate", new Setting("economy.dividend.release_rate", 0, 1));
        SETTINGS.put("dividend.cap", new Setting("economy.dividend.cap", 0, com.vodhanel.minecraft.va_postal.economy.DailyMath.MAX_CAP));
        SETTINGS.put("upkeep.base", new Setting("economy.upkeep.base", 0, Double.MAX_VALUE));
        SETTINGS.put("upkeep.per_address", new Setting("economy.upkeep.per_address", 0, Double.MAX_VALUE));
        SETTINGS.put("upkeep.per_waypoint", new Setting("economy.upkeep.per_waypoint", 0, Double.MAX_VALUE));
        SETTINGS.put("upkeep.revenue_rate", new Setting("economy.upkeep.revenue_rate", 0, 1));
        SETTINGS.put("office_floor", new Setting("economy.office_floor", 0, Double.MAX_VALUE));
        SETTINGS.put("central_buffer", new Setting("economy.central_buffer", 0, Double.MAX_VALUE));
        SETTINGS.put("day_seconds", new Setting("economy.day_seconds", 60, 7 * 86400));
    }

    /** {@code /postal bank policy [<setting> <value>]}: show or change the economy dials, saved to config. */
    private static void policy(CommandSender sender, String[] args) {
        if (args.length < 4) {
            send(sender, "&6[Postal] Economy policy &7(/postal bank policy <setting> <value>)");
            for (java.util.Map.Entry<String, Setting> e : SETTINGS.entrySet()) {
                String value = VA_postal.plugin.getConfig().getString(GetConfig.path_format(e.getValue().path));
                Setting st = e.getValue();
                String range = st.choices != null ? String.join(" | ", st.choices)
                        : (st.max == Double.MAX_VALUE ? ">= " + trim(st.min) : trim(st.min) + " to " + trim(st.max));
                send(sender, "&e" + e.getKey() + "&7: &f" + value + " &7(" + range + ")");
            }
            return;
        }
        String key = args[2].toLowerCase();
        Setting st = SETTINGS.get(key);
        if (st == null) {
            send(sender, "&7Unknown setting '" + args[2] + "'. Settings: " + String.join(", ", SETTINGS.keySet()));
            return;
        }
        String value = args[3].trim();
        if (st.choices != null) {
            String chosen = null;
            for (String c : st.choices) {
                if (c.equalsIgnoreCase(value)) {
                    chosen = c;
                }
            }
            if (chosen == null) {
                send(sender, "&7" + key + " must be one of: " + String.join(", ", st.choices));
                return;
            }
            value = chosen;
        } else {
            double v;
            try {
                v = Double.parseDouble(value);
            } catch (NumberFormatException e) {
                send(sender, "&7" + key + " needs a number.");
                return;
            }
            if (Double.isNaN(v) || v < st.min || v > st.max) {
                send(sender, "&7" + key + " must be " + (st.max == Double.MAX_VALUE ? "at least " + trim(st.min)
                        : "between " + trim(st.min) + " and " + trim(st.max)) + ".");
                return;
            }
            value = trim(v);
        }
        VA_postal.plugin.getConfig().set(GetConfig.path_format(st.path), value);
        VA_postal.plugin.saveConfig();
        send(sender, "&6" + key + " set to " + value + ".");
        Util.cinform("[Postal] Economy policy: " + key + " = " + value + " (by " + sender.getName() + ")");
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
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
