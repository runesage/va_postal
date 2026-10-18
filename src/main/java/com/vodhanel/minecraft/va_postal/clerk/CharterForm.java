package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.deed.Applications;
import com.vodhanel.minecraft.va_postal.deed.Charters;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.deed.Deeds;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * <i>Charter a post office</i> at Central's counter (docs/design/central-office.md §3): the player names the office
 * and pays the post office price; the charter is handed over, or the application goes to the admins.
 */
final class CharterForm {
    private CharterForm() {
    }

    static void open(Player player, String name, String problem) {
        if (!player.hasPermission("postal.charter.buy")) {
            Clerks.say(Clerks.CENTRAL, player, "I'm sorry, I can't charter an office for you.");
            return;
        }
        boolean approval = Charters.approval_required();
        double price = Charters.price();
        List<String> body = new ArrayList<>();
        if (problem != null) {
            body.add("&c" + problem);
        }
        body.add("Name the post office you would found, up to 15 letters.");
        body.add("A charter costs &f" + (VA_postal.economy_configured && price > 0.0D ? P_Economy.ef(price) : "nothing") + "&r.");
        if (approval) {
            body.add("&7Charters are granted by the Postmaster General: you pay now, and have it back if it's declined.");
        }
        body.add("&7Then place the charter on the chest that will be the office's, at least " + Charters.min_distance()
                + " blocks from any other post office.");
        Forms.show(player, new Forms.Form("Charter a post office", body, "Office name", name == null ? "" : name, 32,
                List.of(new Forms.Choice(approval ? "&aApply" : "&aBuy", "buy", (p, value) -> buy(p, value)))));
    }

    static void buy(Player player, String value) {
        if (value == null || value.isBlank()) {
            open(player, null, "The office needs a name.");
            return;
        }
        boolean application = Charters.approval_required();
        double price = VA_postal.economy_configured ? Charters.price() : 0.0D;
        Deeds.Result r = Charters.issue(player, player.getName(), value, price, application);
        if (!r.ok()) {
            open(player, value, BuyForm.capital(r.problem()) + ".");
            return;
        }
        String office = DeedDocument.display(r.deed().office());
        if (application) {
            Applications.applied(r.deed());
            Clerks.say(Clerks.CENTRAL, player, "Your application for the " + office + " Post Office is with the Postmaster General."
                    + " You'll hear when it's decided; if it's declined, you'll have your money back.");
            return;
        }
        Clerks.say(Clerks.CENTRAL, player, "Here is your charter for the " + office + " Post Office. Place it on the chest that will"
                + " be the office's before " + new SimpleDateFormat("d MMM yyyy").format(new Date(r.deed().expires_at())) + ".");
    }
}
