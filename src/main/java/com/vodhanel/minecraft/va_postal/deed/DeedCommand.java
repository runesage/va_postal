package com.vodhanel.minecraft.va_postal.deed;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.store.Deed;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
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
import java.util.UUID;

/**
 * {@code /postal deed ...}: staff tools for address deeds (docs/design/deeds.md). Players buy deeds from an office's
 * clerk; these issue, list and look after them by hand, and drive them from the console for tests.
 */
public final class DeedCommand {
    private DeedCommand() {
    }

    public static void command(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
        switch (sub) {
            case "give" -> give(sender, args, false);
            case "sell" -> give(sender, args, true);
            case "list" -> list(sender, args);
            case "names" -> names(sender, args);
            case "reissue" -> reissue(sender, args);
            case "expire" -> Deeds.inform(sender, "&7Expired " + Deeds.expire_due() + " deed(s).");
            case "test" -> test(sender, args);
            default -> usage(sender);
        }
    }

    private static void usage(CommandSender sender) {
        Deeds.inform(sender, "&7/postal deed give <player> <office> [name]  &8- a free address deed (no name: one is suggested)");
        Deeds.inform(sender, "&7/postal deed sell <player> <office> [name]  &8- the same, charged the address price");
        Deeds.inform(sender, "&7/postal deed list [player]  &8- a player's deeds (yours, if you're a player)");
        Deeds.inform(sender, "&7/postal deed names <office> [count]  &8- house names the office would suggest");
        Deeds.inform(sender, "&7/postal deed reissue <player> <deed>  &8- a new copy of a lost deed (older copies stop working)");
        Deeds.inform(sender, "&7/postal deed expire  &8- expire the deeds whose time is up now (normally the Postal day)");
        Deeds.inform(sender, "&7/postal deed test <office> <name> <x> <y> <z> [world]  &8- use a server deed on that chest");
    }

    private static void give(CommandSender sender, String[] args, boolean charge) {
        if (args.length < 4) {
            usage(sender);
            return;
        }
        OfflinePlayer holder = player(args[2]);
        if (holder == null) {
            Deeds.inform(sender, "&7No player called " + args[2] + " has played here.");
            return;
        }
        String office = C_Postoffice.town_complete(args[3]);
        String name = args.length > 4 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : null;
        if (name == null && !"null".equals(office)) {
            name = Deeds.suggest_name(office, holder.getPlayer() == null ? null : holder.getPlayer().getLocation());
        }
        double price = charge && VA_postal.economy_configured ? C_Economy.addr_purchase_price() : 0.0D;
        Deeds.Result r = Deeds.issue_address(holder, holder.getName() == null ? args[2] : holder.getName(), args[3],
                name == null ? "" : name, price);
        if (!r.ok()) {
            Deeds.inform(sender, "&7No deed: " + r.problem() + ".");
            return;
        }
        Deeds.inform(sender, "&7Deed " + short_id(r.deed().id()) + " for &f" + DeedDocument.display(r.deed().name()) + ", "
                + DeedDocument.display(r.deed().office()) + " &7issued to " + r.deed().holder_name()
                + (price > 0.0D ? " for " + P_Economy.ef(price) : "") + (holder.isOnline() ? "." : " (they'll get the document when it's reissued)."));
    }

    private static void list(CommandSender sender, String[] args) {
        MailStore store = MailStores.active();
        OfflinePlayer who = args.length > 2 ? player(args[2]) : sender instanceof Player p ? p : null;
        if (store == null || who == null) {
            Deeds.inform(sender, store == null ? "&7The mail store isn't open." : "&7Whose deeds? /postal deed list <player>");
            return;
        }
        List<Deed> deeds = store.deeds_of(who.getUniqueId());
        Deeds.inform(sender, "&e[Postal] Deeds of " + who.getName() + ": " + deeds.size());
        SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy");
        for (Deed d : deeds) {
            Deeds.inform(sender, "&7" + short_id(d.id()) + " &f" + DeedDocument.display(d.name()) + ", " + DeedDocument.display(d.office())
                    + " &7" + d.state().name().toLowerCase(java.util.Locale.ROOT)
                    + (d.state().open() ? ", until " + date.format(new Date(d.expires_at())) : ""));
        }
    }

    private static void names(CommandSender sender, String[] args) {
        if (args.length < 3 || "null".equals(C_Postoffice.town_complete(args[2]))) {
            Deeds.inform(sender, "&7Which post office? /postal deed names <office> [count]");
            return;
        }
        String office = C_Postoffice.town_complete(args[2]);
        int count = args.length > 3 ? Math.max(1, Math.min(20, parse(args[3], 5))) : 5;
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (int i = 0; i < count * 4 && names.size() < count; i++) {
            String n = Deeds.suggest_name(office, sender instanceof Player p ? p.getLocation() : null);
            if (n != null) {
                names.add(DeedDocument.display(n));
            }
        }
        String out = String.join(", ", names);
        Deeds.inform(sender, "&7Suggestions for " + DeedDocument.display(office) + ": &f" + out);
    }

    private static void reissue(CommandSender sender, String[] args) {
        MailStore store = MailStores.active();
        OfflinePlayer who = args.length > 3 ? player(args[2]) : null;
        if (store == null || who == null) {
            usage(sender);
            return;
        }
        String prefix = args[3].toLowerCase(java.util.Locale.ROOT);
        Deed match = store.deeds_of(who.getUniqueId()).stream().filter(d -> d.id().toString().startsWith(prefix)).findFirst().orElse(null);
        if (match == null) {
            Deeds.inform(sender, "&7" + who.getName() + " has no deed " + args[3] + ".");
            return;
        }
        Deeds.Result r = Deeds.reissue(match.id());
        Deeds.inform(sender, r.ok() ? "&7Reissued deed " + short_id(match.id()) + " (copy " + r.deed().issue() + ")"
                + (who.isOnline() ? "." : "; " + who.getName() + " is offline, so reissue it again when they're on.")
                : "&7Not reissued: " + r.problem() + ".");
    }

    /**
     * Console test helper: a free deed held by the server, used straight away on the chest at x y z.
     * {@code test <office> <name...> <x> <y> <z> [world]}: the place is read from the end, so a name may have spaces.
     */
    private static void test(CommandSender sender, String[] args) {
        int end = args.length;
        World world = Bukkit.getWorlds().get(0);
        if (end > 0 && parse(args[end - 1]) == null && Bukkit.getWorld(args[end - 1]) != null) {
            world = Bukkit.getWorld(args[end - 1]);
            end--;
        }
        if (end < 7) {
            usage(sender);
            return;
        }
        Integer x = parse(args[end - 3]), y = parse(args[end - 2]), z = parse(args[end - 1]);
        if (x == null || y == null || z == null) {
            Deeds.inform(sender, "&7Bad place: " + String.join(" ", Arrays.copyOfRange(args, end - 3, args.length)));
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 3, end - 3));
        Deeds.Result r = Deeds.issue_address(VA_postal.SERVER, "Server", args[2], name, 0.0D);
        if (!r.ok()) {
            Deeds.inform(sender, "&7No deed: " + r.problem() + ".");
            return;
        }
        Block chest = world.getBlockAt(x, y, z);
        Deeds.inform(sender, "&7Deed " + short_id(r.deed().id()) + " issued; using it on " + x + " " + y + " " + z + "...");
        Deeds.use_as_admin(r.deed(), chest, outcome -> Deeds.inform(sender, "[Postal] Deed " + short_id(r.deed().id()) + ": " + outcome));
    }

    private static OfflinePlayer player(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
    }

    static String short_id(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static Integer parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parse(String s, int def) {
        Integer v = parse(s);
        return v == null ? def : v;
    }
}
