package com.vodhanel.minecraft.va_postal.deed;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.store.Deed;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * {@code /postal charter ...}: staff tools for charters (docs/design/central-office.md §3). Players apply at Central's
 * counter; staff decide the applications here, and can issue charters by hand and drive them from the console.
 */
public final class CharterCommand {
    private CharterCommand() {
    }

    public static void command(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "give" -> issue(sender, args, false, false);
            case "sell" -> issue(sender, args, true, false);
            case "apply" -> issue(sender, args, true, true);
            case "pending" -> pending(sender);
            case "approve", "deny" -> decide(sender, args, "approve".equals(sub));
            case "autoapprove" -> autoapprove(sender, args);
            case "test" -> test(sender, args);
            default -> usage(sender);
        }
    }

    private static void usage(CommandSender sender) {
        Deeds.inform(sender, "&7/postal charter pending  &8- applications waiting for a decision");
        Deeds.inform(sender, "&7/postal charter approve <id | name | all>  &8|  &7deny <id | name> [reason]");
        Deeds.inform(sender, "&7/postal charter autoapprove [on | off]  &8- sell charters without approval (" + (Charters.approval_required() ? "off" : "on") + ")");
        Deeds.inform(sender, "&7/postal charter give <player> <office>  &8- a free charter;  &7sell&8: at the post office price");
        Deeds.inform(sender, "&7/postal charter apply <player> <office>  &8- an application, as made at Central's counter");
        Deeds.inform(sender, "&7/postal charter test <office> <x> <y> <z> [world]  &8- open an office there with a server charter");
    }

    /** give / sell / apply {@code <player|server> <office...>}. */
    private static void issue(CommandSender sender, String[] args, boolean charge, boolean application) {
        if (args.length < 4) {
            usage(sender);
            return;
        }
        OfflinePlayer holder = DeedCommand.player(args[2]);
        if (holder == null) {
            Deeds.inform(sender, "&7No player called " + args[2] + " has played here.");
            return;
        }
        boolean server = holder == VA_postal.SERVER;
        String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        double price = charge && !server && VA_postal.economy_configured ? Charters.price() : 0.0D;
        Deeds.Result r = Charters.issue(holder, holder.getName() == null ? args[2] : holder.getName(), name, price, application);
        if (!r.ok()) {
            Deeds.inform(sender, "&7No charter: " + r.problem() + ".");
            return;
        }
        String what = DeedDocument.display(r.deed().office());
        if (application) {
            Applications.applied(r.deed());
            Deeds.inform(sender, "&7Charter application " + DeedCommand.short_id(r.deed().id()) + " for &f" + what + "&7 by "
                    + r.deed().holder_name() + (price > 0.0D ? ", paid " + P_Economy.ef(price) : "") + ", waits for a decision.");
        } else {
            Deeds.inform(sender, "&7Charter " + DeedCommand.short_id(r.deed().id()) + " for &f" + what + "&7 issued to "
                    + r.deed().holder_name() + (price > 0.0D ? " for " + P_Economy.ef(price) : "") + ".");
        }
    }

    private static void pending(CommandSender sender) {
        List<Deed> waiting = Charters.pending();
        Deeds.inform(sender, "&e[Postal] Charter applications: " + waiting.size() + (Charters.approval_required() ? "" : " &7(autoapprove is on)"));
        SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy");
        for (Deed d : waiting) {
            Deeds.inform(sender, "&7" + DeedCommand.short_id(d.id()) + " &f" + DeedDocument.display(d.office()) + " &7from " + d.holder_name()
                    + (d.price() > 0.0D && VA_postal.economy_configured ? ", paid " + P_Economy.ef(d.price()) : "")
                    + ", applied " + date.format(new Date(d.created_at())) + ", lapses " + date.format(new Date(d.expires_at())));
        }
    }

    private static void decide(CommandSender sender, String[] args, boolean approve) {
        if (args.length < 3) {
            usage(sender);
            return;
        }
        String which = args[2].toLowerCase(Locale.ROOT);
        String name = HouseNames.stored(args[2]);
        List<Deed> waiting = Charters.pending();
        List<Deed> chosen = approve && "all".equals(which) ? waiting
                : waiting.stream().filter(d -> d.id().toString().startsWith(which) || d.office().equals(name)).limit(1).toList();
        if (chosen.isEmpty()) {
            Deeds.inform(sender, "&7No charter application " + args[2] + " waits.");
            return;
        }
        String by = sender instanceof Player p ? p.getName() : "the console";
        String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;
        for (Deed d : chosen) {
            Deeds.Result r = approve ? Applications.approve(d.id(), by) : Applications.deny(d.id(), by, reason);
            Deeds.inform(sender, r.ok() ? "&7" + (approve ? "Approved" : "Declined") + " the charter for " + DeedDocument.display(d.office())
                    + " (" + d.holder_name() + ")." : "&7" + DeedDocument.display(d.office()) + ": " + r.problem() + ".");
        }
    }

    private static void autoapprove(CommandSender sender, String[] args) {
        if (args.length > 2) {
            String v = args[2].toLowerCase(Locale.ROOT);
            if (!List.of("on", "off").contains(v)) {
                usage(sender);
                return;
            }
            Charters.require_approval("off".equals(v));
        }
        Deeds.inform(sender, Charters.approval_required() ? "&7Charter applications wait for an admin's approval."
                : "&7Charters are sold straight away (autoapprove is on).");
    }

    /** {@code test <office...> <x> <y> <z> [world]}: a free server charter, used on the chest there at once. */
    private static void test(CommandSender sender, String[] args) {
        int end = args.length;
        World world = Bukkit.getWorlds().get(0);
        if (end > 0 && DeedCommand.parse(args[end - 1]) == null && Bukkit.getWorld(args[end - 1]) != null) {
            world = Bukkit.getWorld(args[end - 1]);
            end--;
        }
        if (end < 6) {
            usage(sender);
            return;
        }
        Integer x = DeedCommand.parse(args[end - 3]), y = DeedCommand.parse(args[end - 2]), z = DeedCommand.parse(args[end - 1]);
        if (x == null || y == null || z == null) {
            Deeds.inform(sender, "&7Bad place: " + String.join(" ", Arrays.copyOfRange(args, end - 3, args.length)));
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 2, end - 3));
        Deeds.Result r = Charters.issue(VA_postal.SERVER, "Server", name, 0.0D, false);
        if (!r.ok()) {
            Deeds.inform(sender, "&7No charter: " + r.problem() + ".");
            return;
        }
        Block chest = world.getBlockAt(x, y, z);
        Charters.use_as_admin(r.deed(), chest, outcome -> Deeds.inform(sender, "[Postal] Charter " + DeedCommand.short_id(r.deed().id()) + ": " + outcome));
    }
}
