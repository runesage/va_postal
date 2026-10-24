package com.vodhanel.minecraft.va_postal.deed;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Bank;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.economy.EconomyState;
import com.vodhanel.minecraft.va_postal.economy.Reserves;
import com.vodhanel.minecraft.va_postal.mail.BookManip;
import com.vodhanel.minecraft.va_postal.store.ConflictException;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.Offer;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Selling a post office (docs/design/central-office.md §3), only ever through Central:
 * <ul>
 *   <li><b>back to Central</b>: the owner gets what an office changing hands already pays (its balance less the
 *   address escrow it holds, plus Central's share of the purchase price); the office stays open as a server office;</li>
 *   <li><b>to another player</b>: the owner offers it for a price; the buyer accepts at Central's counter, paying the
 *   price to the seller and {@code Settings.Charters.Transfer_rate} (default 5%) of it to Central. The office keeps its
 *   account; nothing is refunded by the system. An offer lapses after {@code Settings.Charters.Offer_days} (default 3),
 *   and the owner can withdraw it.</li>
 * </ul>
 */
public final class OfficeSales {
    private static final long DAY = 24L * 60L * 60L * 1000L;

    private OfficeSales() {
    }

    private static MailStore store() {
        return MailStores.active();
    }

    /** The outcome: the offer when it worked, otherwise why not. */
    public record Result(Offer offer, String problem) {
        static Result failed(String problem) {
            return new Result(null, problem);
        }

        public boolean ok() {
            return problem == null;
        }
    }

    public static double transfer_rate() {
        return Math.max(0.0D, Math.min(1.0D, GetConfig.double_value(VA_postal.plugin.getConfig(),
                GetConfig.path_format("settings.charters.transfer_rate"), 0.05D)));
    }

    public static int offer_days() {
        return Math.max(1, GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.charters.offer_days"), 3));
    }

    /** Central's fee on a sale for {@code price}, to the cent. */
    public static double fee(double price, double rate) {
        return Math.round(Math.max(0.0D, price) * rate * 100.0D) / 100.0D;
    }

    /** A price as typed: a finite, non-negative amount to the cent, or null. */
    static Double parse_price(String s) {
        try {
            double v = Double.parseDouble(s.trim().replace(",", ""));
            return Double.isFinite(v) && v >= 0.0D && v <= 1.0E12D ? Math.round(v * 100.0D) / 100.0D : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- Offers -----------------------------------------------------------------------------

    /** The office's owner offers it to {@code buyer} for {@code price}. */
    public static Result offer(String office, OfflinePlayer buyer, String buyer_name, double price) {
        MailStore store = store();
        UUID seller = C_Owner.get_owner_local_po_id(office);
        if (store == null) {
            return Result.failed("the mail store isn't open");
        }
        if (seller == null) {
            return Result.failed(DeedDocument.display(office) + " belongs to the server; it isn't for sale");
        }
        if (seller.equals(buyer.getUniqueId())) {
            return Result.failed("you can't sell an office to its owner");
        }
        String town = com.vodhanel.minecraft.va_postal.towny.TownyPostOffices.sale_problem(office);
        if (town != null) {
            return Result.failed(town);
        }
        long now = System.currentTimeMillis();
        String seller_name = C_Owner.get_owner_local_po_name(office);
        Offer o = new Offer(UUID.randomUUID(), store.server_id(), office, seller, seller_name == null ? "?" : seller_name,
                buyer.getUniqueId(), buyer_name, price, Offer.State.OPEN, now, now + offer_days() * DAY, now);
        try {
            store.create_offer(o);
        } catch (ConflictException e) {
            return Result.failed(DeedDocument.display(office) + " is already on offer; withdraw that offer first");
        } catch (StoreException e) {
            return Result.failed("the mail store couldn't record it");
        }
        tell(buyer.getUniqueId(), o.seller_name() + " offers you the " + DeedDocument.display(office) + " Post Office for "
                + money(price) + (fee(price, transfer_rate()) > 0.0D ? " (plus Central's fee of " + money(fee(price, transfer_rate())) + ")" : "")
                + ". Accept or decline it at Central's counter before " + date(o.expires_at()) + ".");
        Util.cinform("[Postal] " + o.seller_name() + " offered " + office + " to " + buyer_name + " for " + price);
        return new Result(o, null);
    }

    /** The seller takes back the office's open offer. */
    public static Result withdraw(String office) {
        MailStore store = store();
        Offer o = store == null ? null : store.open_offer(office).orElse(null);
        if (o == null) {
            return Result.failed(DeedDocument.display(office) + " isn't on offer");
        }
        if (!store.offer_state(o.id(), Offer.State.OPEN, Offer.State.WITHDRAWN, System.currentTimeMillis())) {
            return Result.failed("the offer has just been settled");
        }
        tell(o.buyer(), o.seller_name() + " has withdrawn the offer of the " + DeedDocument.display(office) + " Post Office.");
        return new Result(o, null);
    }

    /** The buyer says no. */
    public static Result decline(Offer o, Player buyer) {
        MailStore store = store();
        if (store == null || !o.buyer().equals(buyer.getUniqueId())
                || !store.offer_state(o.id(), Offer.State.OPEN, Offer.State.DECLINED, System.currentTimeMillis())) {
            return Result.failed("that offer is no longer open");
        }
        tell(o.seller(), o.buyer_name() + " has declined your offer of the " + DeedDocument.display(o.office()) + " Post Office.");
        return new Result(o, null);
    }

    /** The buyer accepts at Central's counter: pays the seller and Central's fee, and the office is theirs. */
    public static Result accept(Offer o, Player buyer) {
        MailStore store = store();
        if (store == null) {
            return Result.failed("the mail store isn't open");
        }
        Offer now_o = store.offer(o.id()).orElse(null);
        if (now_o == null || now_o.state() != Offer.State.OPEN || !now_o.buyer().equals(buyer.getUniqueId())) {
            return Result.failed("that offer is no longer open");
        }
        long now = System.currentTimeMillis();
        if (now_o.expires_at() <= now) {
            return Result.failed("that offer has lapsed");
        }
        if (!now_o.seller().equals(C_Owner.get_owner_local_po_id(now_o.office()))) {
            store.offer_state(now_o.id(), Offer.State.OPEN, Offer.State.WITHDRAWN, now);
            return Result.failed(now_o.seller_name() + " no longer owns " + DeedDocument.display(now_o.office()));
        }
        double price = now_o.price(), fee = fee(price, transfer_rate());
        boolean economy = VA_postal.economy_configured && price + fee > 0.0D;
        if (economy && !P_Economy.withdraw_from_player(buyer, price + fee)) {
            return Result.failed("you can't pay " + money(price + fee));
        }
        if (!store.offer_state(now_o.id(), Offer.State.OPEN, Offer.State.ACCEPTED, now)) {
            if (economy) {
                P_Economy.deposit_to_player(buyer, price + fee);
            }
            return Result.failed("that offer has just been settled");
        }
        if (economy) {
            OfflinePlayer seller = Bukkit.getOfflinePlayer(now_o.seller());
            P_Economy.create_player_account(seller);
            if (price > 0.0D && !P_Economy.deposit_to_player(seller, price)) {
                P_Economy.deposit_to_central(price); // kept for the seller by Central rather than lost
                Util.cinform("[Postal] Couldn't pay " + now_o.seller_name() + " for " + now_o.office() + "; Central holds " + price);
            }
            if (fee > 0.0D) {
                P_Economy.deposit_to_central(fee);
                EconomyState.record(EconomyState.Flow.TRANSFER_FEE, fee);
            }
        }
        C_Owner.set_owner_local_po(now_o.office(), buyer);
        resign(now_o.office());
        tell(now_o.seller(), now_o.buyer_name() + " has bought the " + DeedDocument.display(now_o.office()) + " Post Office"
                + (price > 0.0D && VA_postal.economy_configured ? "; " + money(price) + " has been paid to you." : "."));
        Util.cinform("[Postal] " + now_o.buyer_name() + " bought " + now_o.office() + " from " + now_o.seller_name() + " for " + price);
        return new Result(now_o.with_state(Offer.State.ACCEPTED, now), null);
    }

    /** {@code buyer}'s open offers here. */
    public static List<Offer> offers_to(UUID buyer) {
        MailStore store = store();
        return store == null ? List.of() : store.offers_to(buyer);
    }

    public static Offer open_offer(String office) {
        MailStore store = store();
        return store == null ? null : store.open_offer(office).orElse(null);
    }

    /** Lapses the offers whose time is up; both sides hear. Returns how many. */
    public static int expire_due() {
        MailStore store = store();
        if (store == null) {
            return 0;
        }
        int n = 0;
        long now = System.currentTimeMillis();
        for (Offer o : store.offers_due(now)) {
            if (store.offer_state(o.id(), Offer.State.OPEN, Offer.State.EXPIRED, now)) {
                n++;
                String what = "The offer of the " + DeedDocument.display(o.office()) + " Post Office to " + o.buyer_name() + " has lapsed.";
                tell(o.seller(), what);
                tell(o.buyer(), what);
            }
        }
        return n;
    }

    // ---- Selling back to Central ------------------------------------------------------------

    /** What selling {@code office} back to Central pays its owner now (0 without the economy). */
    public static double sell_back_value(String office) {
        if (!VA_postal.economy_configured) {
            return 0.0D;
        }
        double balance = Math.max(0.0D, P_Economy.local_balance(office) - P_Bank.office(office).liability);
        double price = C_Economy.po_purchase_price();
        return balance + Math.max(0.0D, price - Reserves.office_seed(price, C_Economy.office_floor()));
    }

    /** The owner sells {@code office} back to Central: it becomes a server office, and they're paid as above. */
    public static Result sell_back(Player owner, String office) {
        if (!owner.getUniqueId().equals(C_Owner.get_owner_local_po_id(office))) {
            return Result.failed("you don't own " + DeedDocument.display(office));
        }
        String town = com.vodhanel.minecraft.va_postal.towny.TownyPostOffices.sale_problem(office);
        if (town != null) {
            return Result.failed(town);
        }
        Offer open = open_offer(office);
        if (open != null) {
            withdraw(office);
        }
        P_Economy.synchronize_bank_owner(owner, office, VA_postal.SERVER);
        resign(office);
        Util.cinform("[Postal] " + owner.getName() + " sold " + office + " back to Central");
        return new Result(null, null);
    }

    /** Puts the office's sign up again, with its new owner on the last line. */
    public static void resign(String office) {
        Block chest = com.vodhanel.minecraft.va_postal.clerk.Clerks.office_chest(office);
        if (chest != null) {
            BookManip.standard_addr_sign(Util.location2str(chest.getLocation()), 1, office, "[Local]", null);
        }
    }

    // ---- /postal office <office> sell ... -----------------------------------------------------

    /**
     * {@code sell} (the open offer), {@code sell <player> <price>} (offer it), {@code sell cancel} (withdraw the offer),
     * {@code sell central} (sell it back to Central, in person at the counter or with this). Owners for their offices;
     * admins and the console may offer or withdraw on the owner's behalf.
     */
    public static void command(org.bukkit.command.CommandSender sender, String office, String[] args) {
        Player player = sender instanceof Player p ? p : null;
        boolean owner = player != null && player.getUniqueId().equals(C_Owner.get_owner_local_po_id(office));
        boolean admin = player == null || player.hasPermission("postal.admin");
        String name = DeedDocument.display(office);
        if (args.length < 4) {
            Offer o = open_offer(office);
            send(sender, o == null ? "&7" + name + " isn't on offer." : "&7" + name + " is offered to " + o.buyer_name() + " for "
                    + money(o.price()) + ", until " + date(o.expires_at()) + ".");
            send(sender, "&7/postal office " + office + " sell <player> <price> | cancel | central");
            return;
        }
        if (!owner && !admin) {
            send(sender, "&7Only the owner of " + name + " can sell it.");
            return;
        }
        String what = args[3].toLowerCase(java.util.Locale.ROOT);
        if ("cancel".equals(what) || "withdraw".equals(what)) {
            Result r = withdraw(office);
            send(sender, r.ok() ? "&7The offer of " + name + " to " + r.offer().buyer_name() + " is withdrawn." : "&7" + capital(r.problem()) + ".");
            return;
        }
        if ("central".equals(what)) {
            if (!owner) {
                send(sender, "&7Only " + name + "'s owner can sell it back to Central.");
                return;
            }
            double value = sell_back_value(office);
            Result r = sell_back(player, office);
            send(sender, r.ok() ? "&7" + name + " is sold back to Central" + (value > 0.0D ? " for " + money(value) : "")
                    + "; it carries on as a server office." : "&7" + capital(r.problem()) + ".");
            return;
        }
        if (args.length < 5) {
            send(sender, "&7Usage: /postal office " + office + " sell <player> <price>");
            return;
        }
        OfflinePlayer buyer = DeedCommand.player(args[3]);
        Double price = parse_price(args[4]);
        if (buyer == null || buyer == VA_postal.SERVER) {
            send(sender, "&7No player called " + args[3] + " has played here.");
            return;
        }
        if (price == null) {
            send(sender, "&7'" + args[4] + "' isn't a price.");
            return;
        }
        Result r = offer(office, buyer, buyer.getName() == null ? args[3] : buyer.getName(), price);
        send(sender, r.ok() ? "&7" + name + " is offered to " + r.offer().buyer_name() + " for " + money(price) + " until "
                + date(r.offer().expires_at()) + "; they accept at Central's counter." : "&7" + capital(r.problem()) + ".");
    }

    private static void send(org.bukkit.command.CommandSender sender, String message) {
        sender.sendMessage(com.vodhanel.minecraft.va_postal.common.Text.color(message));
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- Telling people ---------------------------------------------------------------------

    private static void tell(UUID player, String message) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            Util.pinform(online, "&7&o" + message);
        } else {
            Notices.add(player, message);
        }
    }

    public static String money(double amount) {
        return VA_postal.economy_configured ? P_Economy.ef(amount) : String.format(java.util.Locale.ROOT, "%.2f", amount);
    }

    private static String date(long at) {
        return new SimpleDateFormat("d MMM yyyy").format(new Date(at));
    }
}
