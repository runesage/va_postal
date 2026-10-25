package com.vodhanel.minecraft.va_postal.deed;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.AnsiColor;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.P_MailAdmin;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.economy.EconomyState;
import com.vodhanel.minecraft.va_postal.store.Actor;
import com.vodhanel.minecraft.va_postal.store.Deed;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Address applications (docs/design/deeds.md §3.2): at an office that requires approval, buying an address makes a
 * pending deed (paid for, its name reserved) that the office's owner approves or denies. Approved, it's issued (and
 * good for {@code Deeds.Expiry_days} from then); denied, it's refunded in full and the name is free again; unanswered,
 * it lapses like a deed, refunded in full. The applicant hears by letter if they have an address, in chat if online,
 * otherwise when they next join.
 */
public final class Applications {
    private Applications() {
    }

    private static MailStore store() {
        return MailStores.active();
    }

    // ---- Settings ---------------------------------------------------------------------------

    /** Whether new addresses at {@code office} need its owner's approval: the office's choice, else the server's. */
    public static boolean required(String office) {
        String own = VA_postal.plugin.getConfig().getString(GetConfig.path_format("postoffice.local." + office + ".require_approval"));
        if (own != null && !own.isBlank()) {
            return "true".equalsIgnoreCase(own.trim());
        }
        return GetConfig.bool_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.deeds.require_approval"), false);
    }

    public static boolean office_setting(String office) {
        String own = VA_postal.plugin.getConfig().getString(GetConfig.path_format("postoffice.local." + office + ".require_approval"));
        return own != null && !own.isBlank();
    }

    /** Sets the office's choice ({@code null}: follow the server's). */
    public static void require(String office, Boolean on) {
        VA_postal.plugin.getConfig().set(GetConfig.path_format("postoffice.local." + office + ".require_approval"),
                on == null ? null : on.toString());
        VA_postal.plugin.saveConfig();
    }

    /** True for the console, admins and the office's owner. */
    public static boolean manages(CommandSender sender, String office) {
        if (!(sender instanceof Player p) || p.hasPermission("postal.admin")) {
            return true;
        }
        UUID owner = C_Owner.get_owner_local_po_id(office);
        return owner != null && owner.equals(p.getUniqueId());
    }

    // ---- Applying and deciding --------------------------------------------------------------

    /** The applications waiting at {@code office}, oldest first. */
    public static List<Deed> pending(String office) {
        MailStore store = store();
        return store == null ? List.of() : store.deeds_pending(office);
    }

    /** A new application at its office: the owner hears of it (admins, for an office the server owns). */
    public static void applied(Deed d) {
        boolean charter = d.kind() == Deed.Kind.CHARTER;
        String line = charter ? d.holder_name() + " has applied for a charter for " + subject(d) + ". See /postal charter pending."
                : d.holder_name() + " has applied for " + DeedDocument.display(d.name()) + " at "
                + DeedDocument.display(d.office()) + ". See /postal office " + d.office() + " applications, or the counter.";
        UUID owner = charter ? null : C_Owner.get_owner_local_po_id(d.office()); // charters are the admins' to decide
        if (owner == null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("postal.admin")) {
                    Util.pinform(p, "&e" + line);
                }
            }
            Util.cinform("[Postal] " + line);
            return;
        }
        Player online = Bukkit.getPlayer(owner);
        if (online != null) {
            Util.pinform(online, "&e" + line);
        } else {
            Notices.add(owner, line);
        }
    }

    /** Approves application {@code id}: it's issued, and the applicant gets the deed (now, or at the counter). */
    public static Deeds.Result approve(UUID id, String by) {
        MailStore store = store();
        Deed d = store == null ? null : store.deed(id).orElse(null);
        if (d == null || d.state() != Deed.State.PENDING || !d.server_id().equals(store.server_id())) {
            return Deeds.Result.failed("no application with that id waits here");
        }
        long now = System.currentTimeMillis();
        if (!store.issue_deed(id, now, now + GetConfig.deed_expiry_days() * Deeds.DAY)) {
            return Deeds.Result.failed("someone decided it first");
        }
        Deed issued = store.deed(id).orElse(d);
        boolean handed = Deeds.give(issued);
        boolean charter = d.kind() == Deed.Kind.CHARTER;
        String noun = Deeds.noun(d.kind());
        tell(issued, capital(decider(d)) + " has approved your application for " + subject(d) + ". "
                + (handed ? "Your " + noun + " is in your inventory; use it" : "Collect your " + noun + " at "
                + (charter ? "Central's" : "the") + " counter and use it") + " on " + (charter ? "the chest that will be the office's"
                : "your mailbox chest") + " before " + new SimpleDateFormat("d MMM yyyy").format(new Date(issued.expires_at())) + ".");
        Util.cinform("[Postal] Application " + DeedCommand.short_id(id) + " (" + d.name() + ", " + d.office() + ") approved by " + by);
        return new Deeds.Result(issued, null);
    }

    /** Denies application {@code id}: refunded in full, and the name is free again. */
    public static Deeds.Result deny(UUID id, String by, String reason) {
        MailStore store = store();
        Deed d = store == null ? null : store.deed(id).orElse(null);
        if (d == null || d.state() != Deed.State.PENDING || !d.server_id().equals(store.server_id())) {
            return Deeds.Result.failed("no application with that id waits here");
        }
        long now = System.currentTimeMillis();
        if (!store.deed_state(id, Deed.State.PENDING, Deed.State.DENIED, now)) {
            return Deeds.Result.failed("someone decided it first");
        }
        String refunded = "";
        if (d.price() > 0.0D && VA_postal.economy_configured) {
            OfflinePlayer holder = Bukkit.getOfflinePlayer(d.holder());
            if (P_Economy.withdraw_from_central(d.price())) {
                P_Economy.deposit_to_player(holder, d.price());
                EconomyState.record(EconomyState.Flow.REFUND, d.price());
                refunded = " " + P_Economy.ef(d.price()) + " has been refunded.";
            } else {
                Util.cinform(AnsiColor.RED + "[Postal] Central couldn't refund application " + id);
            }
        }
        tell(d, capital(decider(d)) + " has declined your application for "
                + subject(d) + (reason == null || reason.isBlank() ? "." : ": " + reason.trim()
                + (reason.trim().endsWith(".") ? "" : ".")) + refunded);
        Util.cinform("[Postal] Application " + DeedCommand.short_id(id) + " (" + d.name() + ", " + d.office() + ") denied by " + by);
        return new Deeds.Result(d.with_state(Deed.State.DENIED, now), null);
    }

    /**
     * Tells an applicant about their application: by letter from the office if they have an address on this server,
     * in chat if they're online, and otherwise when they next join.
     */
    static void tell(Deed d, String message) {
        String[] home = home_of(d.holder());
        boolean lettered = false;
        if (home != null) {
            boolean charter = d.kind() == Deed.Kind.CHARTER;
            // A charter's office doesn't exist yet: the Postmaster General's letter is handed in at the applicant's own office.
            String[] posted = P_MailAdmin.post_letter(charter ? home[0] : d.office(), null, home[0], home[1], d.holder_name(), d.holder(),
                    charter ? "Postmaster General" : "Postmaster of " + DeedDocument.display(d.office()),
                    charter ? "Your charter application" : "Your address application",
                    "Dear " + d.holder_name() + ",\n\n" + message + "\n\nThe " + (charter ? "Central" : DeedDocument.display(d.office()))
                            + " Post Office",
                    Actor.system("deeds"));
            lettered = posted[0] != null;
            if (!lettered) {
                Util.dinform("[Postal] No letter for application " + d.id() + ": " + posted[1]);
            }
        }
        Player online = Bukkit.getPlayer(d.holder());
        if (online != null) {
            Util.pinform(online, "&7&o" + message);
        } else if (!lettered) {
            Notices.add(d.holder(), message);
        }
    }

    /** What an application was for: an address's name, or "the Brookfield Post Office" for a charter. */
    public static String subject(Deed d) {
        return d.kind() == Deed.Kind.CHARTER ? "the " + DeedDocument.display(d.name()) + " Post Office" : DeedDocument.display(d.name());
    }

    /** Who decides an application: the office's postmaster, or the Postmaster General for a charter. */
    public static String decider(Deed d) {
        return d.kind() == Deed.Kind.CHARTER ? "the Postmaster General" : "the postmaster of " + DeedDocument.display(d.office());
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** One of {@code player}'s addresses on this server, or their P.O. box: {office, address}, or null. */
    static String[] home_of(UUID player) {
        return com.vodhanel.minecraft.va_postal.mail.Returns.home(player, null);
    }

    // ---- /postal office <office> approval | applications | approve | deny ----------------------

    /** True if {@code action} is one of these subcommands (handled), false otherwise. */
    public static boolean command(CommandSender sender, String office, String[] args) {
        String action = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "approval" -> approval(sender, office, args);
            case "applications" -> list(sender, office);
            case "approve", "deny" -> decide(sender, office, args, "approve".equals(action));
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void approval(CommandSender sender, String office, String[] args) {
        String name = DeedDocument.display(office);
        if (args.length > 3) {
            if (!manages(sender, office)) {
                send(sender, "&7Only the owner of " + name + " can decide that.");
                return;
            }
            String v = args[3].toLowerCase(Locale.ROOT);
            switch (v) {
                case "on", "true", "yes" -> require(office, true);
                case "off", "false", "no" -> require(office, false);
                case "default" -> require(office, null);
                default -> {
                    send(sender, "&7Usage: /postal office " + office + " approval [on | off | default]");
                    return;
                }
            }
        }
        send(sender, "&7New addresses at " + name + (required(office) ? " need the postmaster's approval" : " are sold straight away")
                + (office_setting(office) ? " (the office's choice)." : " (the server's default)."));
    }

    private static void list(CommandSender sender, String office) {
        if (!manages(sender, office)) {
            send(sender, "&7Only the owner of " + DeedDocument.display(office) + " can see its applications.");
            return;
        }
        List<Deed> waiting = pending(office);
        send(sender, "&e[Postal] Applications at " + DeedDocument.display(office) + ": " + waiting.size()
                + (required(office) ? "" : " &7(approval is off)"));
        SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy");
        for (Deed d : waiting) {
            send(sender, "&7" + DeedCommand.short_id(d.id()) + " &f" + DeedDocument.display(d.name()) + " &7from " + d.holder_name()
                    + (d.price() > 0.0D && VA_postal.economy_configured ? ", paid " + P_Economy.ef(d.price()) : "")
                    + ", applied " + date.format(new Date(d.created_at())) + ", lapses " + date.format(new Date(d.expires_at())));
        }
        if (!waiting.isEmpty()) {
            send(sender, "&7/postal office " + office + " approve <id | name | all>  &8|  &7deny <id | name> [reason]");
        }
    }

    private static void decide(CommandSender sender, String office, String[] args, boolean approve) {
        if (!manages(sender, office)) {
            send(sender, "&7Only the owner of " + DeedDocument.display(office) + " can decide its applications.");
            return;
        }
        if (args.length < 4) {
            send(sender, "&7Usage: /postal office " + office + (approve ? " approve <id | name | all>" : " deny <id | name> [reason]"));
            return;
        }
        String by = sender instanceof Player p ? p.getName() : "the console";
        List<Deed> waiting = pending(office);
        String which = args[3].toLowerCase(Locale.ROOT);
        String name = HouseNames.stored(args[3]);
        List<Deed> chosen = approve && "all".equals(which) ? waiting
                : waiting.stream().filter(d -> d.id().toString().startsWith(which) || d.name().equals(name)).limit(1).toList();
        if (chosen.isEmpty()) {
            send(sender, "&7No application " + args[3] + " waits at " + DeedDocument.display(office) + ".");
            return;
        }
        String reason = args.length > 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : null;
        for (Deed d : chosen) {
            Deeds.Result r = approve ? approve(d.id(), by) : deny(d.id(), by, reason);
            send(sender, r.ok() ? "&7" + (approve ? "Approved " : "Declined ") + DeedDocument.display(d.name()) + " for " + d.holder_name() + "."
                    : "&7" + DeedDocument.display(d.name()) + ": " + r.problem() + ".");
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Text.color(message));
    }
}
