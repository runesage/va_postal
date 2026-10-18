package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.deed.Deeds;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * <i>Buy an address</i> (docs/design/deeds.md §3): the player names their house, or takes the suggested name (or
 * draws another), and pays; the deed goes straight into their inventory.
 */
public final class BuyForm {
    private BuyForm() {
    }

    /** Opens the form: {@code name} is the value to offer (null: a suggestion), {@code problem} why the last try failed. */
    static void open(Player player, String office, String name, String problem) {
        if (!player.hasPermission("postal.deed.buy")) {
            Clerks.say(office, player, "I'm sorry, I can't sell you an address.");
            return;
        }
        String offered = name != null ? name : suggestion(office, player);
        List<String> body = new ArrayList<>();
        if (problem != null) {
            body.add("&c" + problem);
        }
        body.add("Name your house, up to 15 letters, or take our suggestion.");
        body.add("A new address at " + DeedDocument.display(office) + " costs &f" + Clerks.price_text(office) + "&r.");
        body.add("&7Then use the deed on the chest that will be your mailbox.");
        Forms.show(player, new Forms.Form("Buy an address: " + DeedDocument.display(office), body, "House name",
                offered, 32, List.of(
                new Forms.Choice("&aBuy", "buy", (p, value) -> buy(p, office, value)),
                new Forms.Choice("Suggest another", "suggest", (p, value) -> open(p, office, null, null)))));
    }

    private static String suggestion(String office, Player player) {
        String n = Deeds.suggest_name(office, player.getLocation());
        return n == null ? "" : DeedDocument.display(n);
    }

    static void buy(Player player, String office, String value) {
        if (value == null || value.isBlank()) {
            open(player, office, null, "Your house needs a name.");
            return;
        }
        Deeds.Result r = Deeds.issue_address(player, player.getName(), office, value, Clerks.price(office));
        if (!r.ok()) {
            open(player, office, value, capital(r.problem()) + ".");
            return;
        }
        SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy");
        Clerks.say(office, player, "Here is your deed for " + DeedDocument.display(r.deed().name())
                + ". Use it on your mailbox chest before " + date.format(new Date(r.deed().expires_at())) + ".");
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
