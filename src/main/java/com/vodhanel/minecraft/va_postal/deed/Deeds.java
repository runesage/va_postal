package com.vodhanel.minecraft.va_postal.deed;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.AnsiColor;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Address;
import com.vodhanel.minecraft.va_postal.config.C_Dispatcher;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.economy.EconomyState;
import com.vodhanel.minecraft.va_postal.mail.BookManip;
import com.vodhanel.minecraft.va_postal.mail.ChestManip;
import com.vodhanel.minecraft.va_postal.navigation.survey.RouteSurvey;
import com.vodhanel.minecraft.va_postal.store.ConflictException;
import com.vodhanel.minecraft.va_postal.store.Deed;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Address deeds (docs/design/deeds.md): an address is bought as a deed and created where its holder uses it on a
 * chest, once the office's postman is known to be able to walk there.
 *
 * Money: the price is taken when the deed is issued and held in Central's account (counted in what Central owes,
 * {@link #held}). When the deed is used it's split as any address purchase (half stays with Central, half goes to
 * the office); when it expires it's refunded less the expiry fee, which Central keeps.
 */
public final class Deeds {
    private static final long DAY = 24L * 60L * 60L * 1000L;
    /** Deeds being used right now (their survey is running), so a second click doesn't use one twice. */
    private static final Set<UUID> in_use = new HashSet<>();

    private Deeds() {
    }

    private static MailStore store() {
        return MailStores.active();
    }

    /** What a deed or a check came to: the deed when it worked, otherwise why not. */
    public record Result(Deed deed, String problem) {
        static Result failed(String problem) {
            return new Result(null, problem);
        }

        public boolean ok() {
            return deed != null;
        }
    }

    // ---- Issuing ----------------------------------------------------------------------------

    /**
     * An address name as stored, or null with the reason in {@code why} if it can't be an address at {@code office}.
     */
    public static String address_name(String office, String input, StringBuilder why) {
        String name = HouseNames.stored(input == null ? "" : input);
        if (name.isEmpty() || !name.matches("[a-z0-9_]+")) {
            why.append("an address name is letters, numbers and spaces");
            return null;
        }
        if (name.length() > HouseNames.MAX_LENGTH) {
            why.append("an address name fits a sign line: at most ").append(HouseNames.MAX_LENGTH).append(" characters");
            return null;
        }
        if (name.equalsIgnoreCase(office)) {
            why.append("an address can't have its post office's name");
            return null;
        }
        if (C_Address.is_address_defined(office, name)) {
            why.append(DeedDocument.display(office)).append(" already has an address called ").append(DeedDocument.display(name));
            return null;
        }
        MailStore store = store();
        if (store != null && store.deed_name_reserved(Deed.Kind.ADDRESS, office, name)) {
            why.append("someone already holds a deed for ").append(DeedDocument.display(name)).append(", ").append(DeedDocument.display(office));
            return null;
        }
        return name;
    }

    /**
     * Issues an address deed at {@code office} named {@code input} to {@code holder}, charging them {@code price}
     * (0: a gift). The document goes into their inventory if they're online.
     */
    public static Result issue_address(OfflinePlayer holder, String holder_name, String office_input, String input, double price) {
        MailStore store = store();
        if (store == null) {
            return Result.failed("the mail store isn't open");
        }
        String office = C_Postoffice.town_complete(office_input);
        if ("null".equals(office)) {
            return Result.failed("there's no post office called " + office_input);
        }
        StringBuilder why = new StringBuilder();
        String name = address_name(office, input, why);
        if (name == null) {
            return Result.failed(why.toString());
        }
        price = Math.max(0.0D, price);
        if (price > 0.0D) {
            if (!VA_postal.economy_configured) {
                price = 0.0D;
            } else if (!P_Economy.withdraw_from_player(holder, price)) {
                return Result.failed(holder_name + " can't pay " + P_Economy.ef(price));
            }
        }
        long now = System.currentTimeMillis();
        Deed deed = new Deed(UUID.randomUUID(), Deed.Kind.ADDRESS, Deed.State.ISSUED, holder.getUniqueId(), holder_name,
                store.server_id(), office, name, price, 1, now, now + GetConfig.deed_expiry_days() * DAY, now);
        try {
            store.create_deed(deed);
        } catch (ConflictException | StoreException e) {
            if (price > 0.0D) {
                P_Economy.deposit_to_player(holder, price); // not recorded: give the money back
            }
            return Result.failed(e instanceof ConflictException ? "someone already holds a deed for that name" : "the mail store couldn't record it");
        }
        if (price > 0.0D) {
            P_Economy.deposit_to_central(price); // held for the deed
            EconomyState.record(EconomyState.Flow.ADDRESS_PURCHASE, price);
        }
        give(deed);
        return new Result(deed, null);
    }

    /** Puts the deed's document in its holder's inventory (dropped at their feet if it's full); false if offline. */
    public static boolean give(Deed deed) {
        Player p = Bukkit.getPlayer(deed.holder());
        if (p == null) {
            return false;
        }
        ItemStack doc = DeedDocument.create(deed, office_world(deed.office()), deed.server_id());
        for (ItemStack left : p.getInventory().addItem(doc).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        return true;
    }

    /** A new copy of an open deed for its holder; older copies stop working. */
    public static Result reissue(UUID id) {
        MailStore store = store();
        if (store == null) {
            return Result.failed("the mail store isn't open");
        }
        Deed deed = store.reissue_deed(id, System.currentTimeMillis()).orElse(null);
        if (deed == null) {
            return Result.failed("no open deed with that id");
        }
        give(deed);
        return new Result(deed, null);
    }

    // ---- Using --------------------------------------------------------------------------------

    /** A player right-clicked {@code chest} holding {@code doc}, a deed: tries to make it their mailbox. */
    public static void use(Player player, Block chest, ItemStack doc) {
        DeedDocument.Ref ref = DeedDocument.read(doc);
        MailStore store = store();
        if (ref == null || store == null) {
            return;
        }
        Deed deed = store.deed(ref.id()).orElse(null);
        String problem = usable(deed, ref, player.getUniqueId(), store.server_id());
        if (problem == null) {
            problem = place_problem(deed, chest);
        }
        if (problem != null) {
            Util.pinform(player, "&7&o" + problem);
            return;
        }
        Util.pinform(player, "&7&oThe " + DeedDocument.display(deed.office()) + " Post Office is checking its postman can reach this chest...");
        apply(deed, chest, player, outcome -> Util.pinform(player, outcome));
    }

    /**
     * Uses {@code deed} on {@code chest} with nobody holding it: the console test helper ({@code /postal deed
     * test}). Same checks on the place, survey and creation as a player's use; no document to take, no ceremony.
     */
    public static void use_as_admin(Deed deed, Block chest, Consumer<String> report) {
        String problem = deed.state() == Deed.State.ISSUED ? place_problem(deed, chest) : "the deed isn't issued";
        if (problem != null) {
            report.accept(problem);
            return;
        }
        apply(deed, chest, null, report);
    }

    /** Why {@code deed}'s document {@code ref} can't be used by {@code user} here, or null. */
    static String usable(Deed deed, DeedDocument.Ref ref, UUID user, String server) {
        if (deed == null) {
            return "This deed isn't on record.";
        }
        if (deed.kind() != Deed.Kind.ADDRESS) {
            return "This document isn't an address deed.";
        }
        if (deed.state() == Deed.State.PENDING) {
            return "This deed is still waiting for approval.";
        }
        if (deed.state() != Deed.State.ISSUED) {
            return "This deed has been " + deed.state().name().toLowerCase(java.util.Locale.ROOT) + "; it can't be used.";
        }
        if (ref.issue() != deed.issue()) {
            return "This copy of the deed has been replaced; use the newer one.";
        }
        if (!deed.holder().equals(user)) {
            return "This deed belongs to " + deed.holder_name() + ".";
        }
        if (!deed.server_id().equals(server)) {
            return "This deed is for a post office on " + deed.server_id() + ".";
        }
        if (deed.expires_at() <= System.currentTimeMillis()) {
            return "This deed has expired.";
        }
        return null;
    }

    /** Why {@code chest} can't become {@code deed}'s mailbox, or null. */
    private static String place_problem(Deed deed, Block chest) {
        if (!ChestManip.ok_to_use_chest(chest, true)) {
            return "This chest is already a Postal chest, or has a sign on it.";
        }
        Location office = Util.str2location(C_Postoffice.get_local_po_location_by_name(deed.office()));
        if (office == null) {
            return "The " + DeedDocument.display(deed.office()) + " Post Office has no location.";
        }
        if (!office.getWorld().equals(chest.getWorld())) {
            return "This chest is in another world from the " + DeedDocument.display(deed.office()) + " Post Office.";
        }
        int max = GetConfig.mail_office_distance();
        if (max > 0 && office.distance(chest.getLocation()) > max) {
            return "This chest is out of the " + DeedDocument.display(deed.office()) + " Post Office's reach (" + max + " blocks).";
        }
        if (C_Address.is_address_defined(deed.office(), deed.name())) {
            return DeedDocument.display(deed.office()) + " already has an address called " + DeedDocument.display(deed.name()) + ".";
        }
        return null;
    }

    /**
     * Surveys a route from the office to the spot in front of {@code chest}; if there is one, creates the address
     * there for the deed's holder and settles the money. {@code report} hears how it went (main thread).
     * {@code player} (may be null, for the admin helper) gets the ceremony and loses the document.
     */
    static void apply(Deed deed, Block chest, Player player, Consumer<String> report) {
        synchronized (in_use) {
            if (!in_use.add(deed.id())) {
                report.accept("&7&oThis deed is already being used.");
                return;
            }
        }
        Block front = front_of(chest);
        RouteSurvey.probe(deed.office(), front.getLocation(), probe -> {
            try {
                if (!probe.ok()) {
                    report.accept("&7&oThe postman couldn't reach this chest from the " + DeedDocument.display(deed.office())
                            + " Post Office: " + probe.message() + ". Make a way through and try again; your deed is unchanged.");
                    return;
                }
                MailStore store = store();
                String problem = place_problem(deed, chest);
                if (problem != null) {
                    report.accept("&7&o" + problem); // changed during the survey
                    return;
                }
                if (store == null || !store.deed_state(deed.id(), Deed.State.ISSUED, Deed.State.USED, System.currentTimeMillis())) {
                    report.accept("&7&oThis deed can't be used any more.");
                    return;
                }
                create_address(deed, chest, front, probe);
                if (player != null) {
                    take_documents(player, deed.id());
                    ceremony(player, chest, deed);
                }
                report.accept("&aWelcome to &f" + DeedDocument.display(deed.name()) + ", " + DeedDocument.display(deed.office()) + "&a!");
            } finally {
                synchronized (in_use) {
                    in_use.remove(deed.id());
                }
            }
        });
    }

    /**
     * Where the postman stands at a mailbox: two blocks out from the chest's front (the sign takes the block
     * against it), as addresses set up by hand are.
     */
    private static Block front_of(Block chest) {
        BlockFace face = chest.getBlockData() instanceof Directional d ? d.getFacing() : BlockFace.SOUTH;
        return chest.getRelative(face, 2);
    }

    private static void create_address(Deed deed, Block chest, Block front, RouteSurvey.Probe probe) {
        String office = deed.office(), name = deed.name();
        // A deed held by the server (the test helper) makes a server-owned address: no owner, none on the sign.
        boolean server_held = VA_postal.SERVER != null && VA_postal.SERVER.getUniqueId().equals(deed.holder());
        OfflinePlayer holder = Bukkit.getOfflinePlayer(deed.holder());
        C_Address.save_postal_address_by_location(Util.location2str(front.getLocation()), office, name);
        C_Address.init_address(office, name);
        if (!server_held) {
            com.vodhanel.minecraft.va_postal.config.C_Owner.set_owner_address(office, name, holder_player(holder));
        }
        // After the owner: the sign's last line is read from it, and the postman finds a player's mailbox by it.
        BookManip.standard_addr_sign(Util.location2str(chest.getLocation()), 1, office, name, null);
        RouteSurvey.Outcome saved = RouteSurvey.save_probe(office, name, probe);
        C_Dispatcher.open_address(office, name, saved.saved());
        if (deed.price() > 0.0D && VA_postal.economy_configured) {
            // Half of the held price goes to the office; Central keeps the other half (docs/economy.md).
            double half = deed.price() / 2.0D;
            if (P_Economy.withdraw_from_central(half)) {
                P_Economy.deposit_to_local(office, half);
            } else {
                Util.cinform(AnsiColor.RED + "[Postal] Central couldn't pass " + half + " to " + office + " for deed " + deed.id());
            }
        }
        Util.cinform("[Postal] Deed " + deed.id() + ": " + deed.holder_name() + " opened " + office + ", " + name);
    }

    /** The owner as C_Owner takes it: the online player, or an offline stand-in with their id and name. */
    private static Player holder_player(OfflinePlayer holder) {
        Player online = holder.getPlayer();
        return online != null ? online : com.vodhanel.minecraft.va_postal.common.ServerPlayer.create(holder.getUniqueId(), holder.getName());
    }

    private static void take_documents(Player player, UUID id) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            DeedDocument.Ref ref = DeedDocument.read(contents[i]);
            if (ref != null && ref.id().equals(id)) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    private static void ceremony(Player player, Block chest, Deed deed) {
        Location at = chest.getLocation().add(0.5D, 1.2D, 0.5D);
        chest.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 30, 0.5D, 0.4D, 0.5D);
        player.playSound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0F, 1.2F);
    }

    // ---- Expiry -----------------------------------------------------------------------------

    /** Expires this server's deeds whose time is up: refunded less the fee, their names released. On the Postal day. */
    public static int expire_due() {
        MailStore store = store();
        if (store == null) {
            return 0;
        }
        int n = 0;
        long now = System.currentTimeMillis();
        for (Deed d : store.deeds_due(now)) {
            if (!store.deed_state(d.id(), d.state(), Deed.State.EXPIRED, now)) {
                continue;
            }
            n++;
            double refund = d.price() * (1.0D - GetConfig.deed_expiry_fee());
            OfflinePlayer holder = Bukkit.getOfflinePlayer(d.holder());
            if (refund > 0.0D && VA_postal.economy_configured) {
                if (P_Economy.withdraw_from_central(refund)) {
                    P_Economy.deposit_to_player(holder, refund);
                    EconomyState.record(EconomyState.Flow.REFUND, refund);
                } else {
                    Util.cinform(AnsiColor.RED + "[Postal] Central couldn't refund deed " + d.id());
                }
            }
            Player online = holder.getPlayer();
            if (online != null) {
                Util.pinform(online, "&7&oYour deed for " + DeedDocument.display(d.name()) + ", " + DeedDocument.display(d.office())
                        + " has lapsed" + (refund > 0.0D && VA_postal.economy_configured ? "; " + P_Economy.ef(refund) + " was refunded." : "."));
            }
        }
        return n;
    }

    /** What open deeds hold in Central's account: part of what Central owes. */
    public static double held() {
        MailStore store = store();
        try {
            return store == null ? 0.0D : store.deeds_held();
        } catch (StoreException e) {
            return 0.0D;
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private static String office_world(String office) {
        Location l = Util.str2location(C_Postoffice.get_local_po_location_by_name(office));
        return l == null ? null : l.getWorld().getName();
    }

    /** A house name for a new address at {@code office}, near {@code at} (the office if null). */
    public static String suggest_name(String office, Location at) {
        Location l = at != null ? at : Util.str2location(C_Postoffice.get_local_po_location_by_name(office));
        String biome = l == null ? null : l.getBlock().getBiome().getKey().toString();
        Set<String> taken = new HashSet<>(RouteSurvey.addresses(office));
        return HouseNames.suggest(Names.words(), HouseNames.setting_of(biome), taken, new java.util.Random());
    }

    static void inform(CommandSender to, String message) {
        if (to instanceof Player p) {
            Util.pinform(p, message);
        } else {
            Util.cinform(com.vodhanel.minecraft.va_postal.common.Text.strip(com.vodhanel.minecraft.va_postal.common.Text.color(message)));
        }
    }
}
