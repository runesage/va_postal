package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.deed.OfficeSales;
import com.vodhanel.minecraft.va_postal.store.Offer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** Central's office-sale menus (docs/design/central-office.md §3): the player's offices to sell back, and offers to them. */
final class SalesMenus {
    private SalesMenus() {
    }

    /** The player's offices; clicking one asks them to confirm selling it back to Central. */
    static final class Owned implements Menu {
        private final List<String> offices;
        private final Inventory inventory;

        Owned(List<String> offices) {
            this.offices = offices.subList(0, Math.min(offices.size(), 26));
            this.inventory = Bukkit.createInventory(this, 27, Text.component(Text.color("&8Your post offices")));
            for (int i = 0; i < this.offices.size(); i++) {
                String office = this.offices.get(i);
                Offer open = OfficeSales.open_offer(office);
                inventory.setItem(i, CounterMenu.item(Material.CHEST, "&f" + DeedDocument.display(office) + " Post Office",
                        VA_postal.economy_configured ? "&7Sell back to Central for about &f" + OfficeSales.money(OfficeSales.sell_back_value(office)) : "&7Sell back to Central",
                        "&7(its balance less the address escrow,", "&7and Central's share of the price)",
                        open == null ? "" : "&eOn offer to " + open.buyer_name() + " (withdrawn if sold back)",
                        "", "&7Click to sell it back."));
            }
            inventory.setItem(26, CounterMenu.item(Material.ARROW, "&7Back to the counter"));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        @Override
        public void click(Player player, int slot, boolean right) {
            if (slot == 26) {
                CentralMenu.open(player);
                return;
            }
            if (slot < 0 || slot >= offices.size()) {
                return;
            }
            player.closeInventory();
            confirm_sell_back(player, offices.get(slot));
        }
    }

    static void confirm_sell_back(Player player, String office) {
        String name = DeedDocument.display(office);
        double value = OfficeSales.sell_back_value(office);
        Forms.show(player, new Forms.Form("Sell " + name + " back to Central", List.of(
                "The " + name + " Post Office goes back to the server and carries on as a server office;",
                "its addresses and residents aren't affected.",
                VA_postal.economy_configured ? "You receive about &f" + OfficeSales.money(value) + "&r." : "",
                "&7Type the office's name to confirm."), "Office", "", 32,
                List.of(new Forms.Choice("&cSell", "sell", (p, typed) -> {
                    if (!name.equalsIgnoreCase(typed.trim()) && !office.equalsIgnoreCase(typed.trim())) {
                        Clerks.say(Clerks.CENTRAL, p, "Very well; " + name + " stays yours.");
                        return;
                    }
                    OfficeSales.Result r = OfficeSales.sell_back(p, office);
                    Clerks.say(Clerks.CENTRAL, p, r.ok() ? "It's done: the " + name + " Post Office is the server's again"
                            + (value > 0.0D ? ", and " + OfficeSales.money(value) + " is yours." : ".")
                            : "I can't do that: " + r.problem() + ".");
                }))));
    }

    /** Offers to the player; left-click accepts, right-click declines. */
    static final class Offers implements Menu {
        private final List<Offer> offers;
        private final Inventory inventory;

        Offers(List<Offer> offers) {
            this.offers = offers.subList(0, Math.min(offers.size(), 26));
            this.inventory = Bukkit.createInventory(this, 27, Text.component(Text.color("&8Offers to you")));
            SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy");
            double rate = OfficeSales.transfer_rate();
            for (int i = 0; i < this.offers.size(); i++) {
                Offer o = this.offers.get(i);
                double fee = OfficeSales.fee(o.price(), rate);
                inventory.setItem(i, CounterMenu.item(Material.PAPER, "&f" + DeedDocument.display(o.office()) + " Post Office",
                        "&7From &f" + o.seller_name(),
                        "&7Price: &f" + OfficeSales.money(o.price()) + (fee > 0.0D ? " &7+ Central's fee &f" + OfficeSales.money(fee) : ""),
                        "&7Until " + date.format(new Date(o.expires_at())),
                        "", "&aLeft-click: accept and pay", "&cRight-click: decline"));
            }
            inventory.setItem(26, CounterMenu.item(Material.ARROW, "&7Back to the counter"));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        @Override
        public void click(Player player, int slot, boolean right) {
            if (slot == 26) {
                CentralMenu.open(player);
                return;
            }
            if (slot < 0 || slot >= offers.size()) {
                return;
            }
            Offer o = offers.get(slot);
            String name = DeedDocument.display(o.office());
            player.closeInventory();
            OfficeSales.Result r = right ? OfficeSales.decline(o, player) : OfficeSales.accept(o, player);
            Clerks.say(Clerks.CENTRAL, player, !r.ok() ? "I can't do that: " + r.problem() + "."
                    : right ? "I'll let " + o.seller_name() + " know you've declined." : "Congratulations: the " + name
                    + " Post Office is yours. Its accounts and residents come with it.");
        }
    }
}
