package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.mail.Claims;
import com.vodhanel.minecraft.va_postal.store.Actor;
import com.vodhanel.minecraft.va_postal.store.MailKind;
import com.vodhanel.minecraft.va_postal.store.MailRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;

/**
 * Lost-mail claims at Central's counter (docs/design/central-office.md §9): one slip per letter or parcel the player
 * sent that went missing. Left-click sends it again from Central; right-click on a parcel takes its items back, with
 * the postage refunded. The last slot goes back to the counter.
 */
final class ClaimsMenu implements Menu {
    static final int SIZE = 54;
    static final int BACK = SIZE - 1;

    private final List<MailRecord> shown;
    private final Inventory inventory;

    private ClaimsMenu(Player player) {
        List<MailRecord> lost = Claims.lost(player.getUniqueId());
        this.shown = lost.subList(0, Math.min(lost.size(), SIZE - 1));
        this.inventory = Bukkit.createInventory(this, SIZE, Text.component(Text.color("&8Lost mail")));
        for (int i = 0; i < shown.size(); i++) {
            MailRecord r = shown.get(i);
            boolean parcel = r.kind == MailKind.PARCEL;
            inventory.setItem(i, CounterMenu.item(parcel ? Material.CHEST : Material.WRITTEN_BOOK,
                    "&f" + Claims.noun(r) + " for " + Util.df(r.dest_address),
                    "&7To &f" + Util.df(r.dest_office) + ", " + Util.df(r.dest_address),
                    "&7Lost " + Claims.when(r),
                    "",
                    "&aLeft-click: send it again",
                    parcel ? "&eRight-click: take its items back" : "",
                    parcel ? "&7(the postage is refunded)" : ""));
        }
        inventory.setItem(BACK, CounterMenu.item(Material.ARROW, "&7Back to the counter"));
    }

    static void open(Player player) {
        player.openInventory(new ClaimsMenu(player).inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void click(Player player, int slot, boolean right) {
        if (slot == BACK) {
            CentralMenu.open(player);
            return;
        }
        if (slot < 0 || slot >= shown.size()) {
            return;
        }
        MailRecord r = shown.get(slot);
        if (right && r.kind == MailKind.PARCEL) {
            player.closeInventory();
            if (Claims.take_back(player, r)) {
                Clerks.say(Clerks.CENTRAL, player, "Here are the things you sent to " + Util.df(r.dest_address)
                        + ", from our records, and your postage back.");
            }
            return;
        }
        String problem = Claims.resend(r, Actor.player(player.getUniqueId()));
        Clerks.say(Clerks.CENTRAL, player, problem == null
                ? "Your " + Claims.noun(r).toLowerCase() + " for " + Util.df(r.dest_address)
                        + " is in our records: it goes out again from here. If the old one turns up, it's void."
                : "I can't send it again now: " + problem + ".");
        open(player);
    }
}
