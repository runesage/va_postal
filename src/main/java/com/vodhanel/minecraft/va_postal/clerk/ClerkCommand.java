package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * {@code /postal clerk ...}: where the offices' clerks stand. Owners station their own office's clerk; admins any.
 * {@code /postal office <office> price ...} sets an office's address price ({@link #price}).
 */
public final class ClerkCommand {
    private ClerkCommand() {
    }

    public static void command(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "list" -> {
                if (!admin(sender)) {
                    send(sender, "&7Required permission not present.");
                    return;
                }
                send(sender, "&e[Postal] Clerks" + (Clerks.enabled() ? "" : " (off: Settings.Clerks.Local)") + ":");
                for (String line : Clerks.describe()) {
                    send(sender, line);
                }
            }
            case "here" -> here(sender, args);
            case "place" -> place(sender, args);
            case "reset" -> reset(sender, args);
            default -> usage(sender);
        }
    }

    private static void usage(CommandSender sender) {
        send(sender, "&7/postal clerk here [office]  &8- station your office's clerk where you stand");
        send(sender, "&7/postal clerk reset <office>  &8- back beside the office chest");
        send(sender, "&7/postal clerk place <office> <x> <y> <z> [world]  &8- station a clerk there (admin)");
        send(sender, "&7/postal clerk list  &8- every office's clerk (admin)");
    }

    private static void here(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&7Only a player can stand somewhere. Use /postal clerk place.");
            return;
        }
        String office = args.length > 2 ? office(args[2]) : nearest(player.getLocation());
        if (office == null) {
            send(sender, args.length > 2 ? "&7No post office matches '" + args[2] + "'." : "&7You're not near a post office.");
            return;
        }
        if (!CounterMenu.manages(player, office)) {
            send(sender, "&7Only the owner of " + DeedDocument.display(office) + " can station its clerk.");
            return;
        }
        Location chest = Util.str2location(C_Postoffice.get_local_po_location_by_name(office));
        int reach = reach();
        if (chest == null || chest.getWorld() != player.getWorld() || chest.distance(player.getLocation()) > reach) {
            send(sender, "&7Stand in " + DeedDocument.display(office) + "'s post office (within " + reach + " blocks of its chest).");
            return;
        }
        Clerks.place(office, player.getLocation());
        send(sender, "&7" + Text.strip(Text.color(Clerks.name(office))) + " will stand here.");
    }

    private static void place(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            send(sender, "&7Required permission not present.");
            return;
        }
        if (args.length < 6) {
            usage(sender);
            return;
        }
        String office = office(args[2]);
        if (office == null) {
            send(sender, "&7No post office matches '" + args[2] + "'.");
            return;
        }
        World world = args.length > 6 ? Bukkit.getWorld(args[6]) : sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
        try {
            if (world == null) {
                throw new NumberFormatException();
            }
            Location at = new Location(world, Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5]));
            Clerks.place(office, at);
            send(sender, "&7The clerk of " + DeedDocument.display(office) + " will stand at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ() + ".");
        } catch (NumberFormatException e) {
            send(sender, "&7Bad place: " + String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
        }
    }

    private static void reset(CommandSender sender, String[] args) {
        String office = args.length > 2 ? office(args[2]) : null;
        if (office == null) {
            usage(sender);
            return;
        }
        if (!admin(sender) && !(sender instanceof Player p && CounterMenu.manages(p, office))) {
            send(sender, "&7Only the owner of " + DeedDocument.display(office) + " can station its clerk.");
            return;
        }
        Clerks.place(office, null);
        send(sender, "&7The clerk of " + DeedDocument.display(office) + " will stand beside the office chest.");
    }

    /** {@code /postal office <office> price [amount|default]}: shows or sets what a new address there costs. */
    public static void price(CommandSender sender, String office, String[] args) {
        if (args.length < 4) {
            send(sender, "&7A new address at " + DeedDocument.display(office) + " costs " + Clerks.price_text(office)
                    + (Clerks.has_price(office) ? " (the office's price)." : " (the server's price)."));
            return;
        }
        if (!admin(sender) && !(sender instanceof Player p && CounterMenu.manages(p, office))) {
            send(sender, "&7Only the owner of " + DeedDocument.display(office) + " can set its prices.");
            return;
        }
        if ("default".equalsIgnoreCase(args[3])) {
            Clerks.set_price(office, null);
        } else {
            Double price = Clerks.parse_price(args[3]);
            if (price == null) {
                send(sender, "&7'" + args[3] + "' isn't a price. Use an amount, or 'default'.");
                return;
            }
            Clerks.set_price(office, price);
        }
        send(sender, "&7A new address at " + DeedDocument.display(office) + " now costs " + Clerks.price_text(office) + ".");
    }

    private static String nearest(Location at) {
        String best = null;
        double best_d = Double.MAX_VALUE;
        for (String office : Clerks.offices()) {
            Location chest = Util.str2location(C_Postoffice.get_local_po_location_by_name(office));
            if (chest != null && chest.getWorld() == at.getWorld() && chest.distanceSquared(at) < best_d) {
                best = office;
                best_d = chest.distanceSquared(at);
            }
        }
        return best != null && best_d <= (double) reach() * reach() ? best : null;
    }

    /** How far from its chest an office's clerk may stand. */
    static int reach() {
        return Math.max(4, GetConfig.int_value(com.vodhanel.minecraft.va_postal.VA_postal.plugin.getConfig(),
                GetConfig.path_format("settings.clerks.reach"), 24));
    }

    private static String office(String input) {
        String office = C_Postoffice.town_complete(input);
        return "null".equals(office) || !Clerks.offices().contains(office) ? null : office;
    }

    private static boolean admin(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission("postal.admin");
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Text.color(message));
    }
}
