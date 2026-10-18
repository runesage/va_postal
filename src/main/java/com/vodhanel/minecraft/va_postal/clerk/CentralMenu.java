package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.deed.Charters;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.deed.Deeds;
import com.vodhanel.minecraft.va_postal.deed.OfficeSales;
import com.vodhanel.minecraft.va_postal.store.Deed;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Central's counter (docs/design/central-office.md §2), opened by the Postmaster General: the network's postage
 * rates, the network at a glance, the offices the player owns, and charters: apply or buy one, see an application, or
 * collect a granted (or lost) charter. Office sales join it later.
 */
final class CentralMenu implements Menu {
    static final int RATES = 11;
    static final int NETWORK = 13;
    static final int OFFICES = 15;
    static final int CHARTERS = 22;
    static final int OFFERS = 24;

    private final Inventory inventory;
    /** The player's charters: issued ones waiting to be used, and applications waiting for a decision. */
    private final List<Deed> issued;
    private final List<Deed> waiting;
    private final List<String> owned;
    private final List<com.vodhanel.minecraft.va_postal.store.Offer> offers;

    private CentralMenu(Player player) {
        this.inventory = Bukkit.createInventory(this, 27, Text.component(Text.color("&8Central Post Office")));
        inventory.setItem(RATES, rates());
        inventory.setItem(NETWORK, network());
        owned = owned(player.getUniqueId());
        if (!owned.isEmpty()) {
            List<String> lore = new ArrayList<>();
            for (String office : owned) {
                lore.add("&f" + DeedDocument.display(office));
            }
            lore.add("");
            lore.add("&7Their accounts: /postal office");
            lore.add("&7Click to sell one back to Central.");
            inventory.setItem(OFFICES, CounterMenu.item(Material.CHEST, "&6Your post offices", lore.toArray(new String[0])));
        }
        issued = new ArrayList<>();
        waiting = new ArrayList<>();
        charters(player.getUniqueId());
        inventory.setItem(CHARTERS, charter_card());
        offers = OfficeSales.offers_to(player.getUniqueId());
        if (!offers.isEmpty()) {
            List<String> lore = new ArrayList<>();
            for (com.vodhanel.minecraft.va_postal.store.Offer o : offers) {
                lore.add("&f" + DeedDocument.display(o.office()) + " &7from " + o.seller_name());
            }
            lore.add("");
            lore.add("&7Click to accept or decline.");
            inventory.setItem(OFFERS, CounterMenu.item(Material.PAPER, "&6Offers to you", lore.toArray(new String[0])));
        }
    }

    private void charters(UUID player) {
        MailStore store = MailStores.active();
        if (store == null) {
            return;
        }
        try {
            for (Deed d : store.deeds_of(player)) {
                if (d.kind() == Deed.Kind.CHARTER && d.server_id().equals(store.server_id()) && d.expires_at() > System.currentTimeMillis()) {
                    if (d.state() == Deed.State.ISSUED) {
                        issued.add(d);
                    } else if (d.state() == Deed.State.PENDING) {
                        waiting.add(d);
                    }
                }
            }
        } catch (StoreException e) {
            // the counter opens without them
        }
    }

    private org.bukkit.inventory.ItemStack charter_card() {
        if (!issued.isEmpty()) {
            List<String> lore = new ArrayList<>();
            lore.add("&7A copy of your charter: granted");
            lore.add("&7ones, or ones you've lost (older");
            lore.add("&7copies stop working).");
            lore.add("");
            for (Deed d : issued) {
                lore.add("&f" + DeedDocument.display(d.office()) + " Post Office");
            }
            return CounterMenu.item(Material.WRITTEN_BOOK, "&6Collect your charter", lore.toArray(new String[0]));
        }
        if (!waiting.isEmpty()) {
            List<String> lore = new ArrayList<>();
            for (Deed d : waiting) {
                lore.add("&f" + DeedDocument.display(d.office()) + " Post Office");
            }
            lore.add("&7waits for the Postmaster General.");
            return CounterMenu.item(Material.CLOCK, "&6Your charter application", lore.toArray(new String[0]));
        }
        double price = Charters.price();
        return CounterMenu.item(Material.WRITABLE_BOOK, Charters.approval_required() ? "&6Apply for a charter" : "&6Buy a charter",
                "&7Found a post office of your own.", "",
                "&7Price: &f" + (VA_postal.economy_configured && price > 0.0D ? P_Economy.ef(price) : "free"),
                Charters.approval_required() ? "&7Granted by the Postmaster General." : "");
    }

    static void open(Player player) {
        player.openInventory(new CentralMenu(player).inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void click(Player player, int slot, boolean right) {
        if (slot == OFFICES && !owned.isEmpty()) {
            player.openInventory(new SalesMenus.Owned(owned).getInventory());
            return;
        }
        if (slot == OFFERS && !offers.isEmpty()) {
            player.openInventory(new SalesMenus.Offers(offers).getInventory());
            return;
        }
        if (slot != CHARTERS) {
            return; // the other cards are for reading
        }
        player.closeInventory();
        if (!issued.isEmpty()) {
            for (Deed d : issued) {
                Deeds.Result r = Deeds.reissue(d.id());
                Clerks.say(Clerks.CENTRAL, player, r.ok() ? "Here is your charter for the " + DeedDocument.display(d.office()) + " Post Office."
                        : "I couldn't copy your charter for " + DeedDocument.display(d.office()) + ": " + r.problem() + ".");
            }
        } else if (waiting.isEmpty()) {
            CharterForm.open(player, null, null);
        } else {
            Clerks.say(Clerks.CENTRAL, player, "Your application is still with the Postmaster General.");
        }
    }

    private static org.bukkit.inventory.ItemStack rates() {
        if (!VA_postal.economy_configured) {
            return CounterMenu.item(Material.PAPER, "&6Postage rates", "&7Postage is free on this server.");
        }
        List<String> lines = new java.util.ArrayList<>(com.vodhanel.minecraft.va_postal.economy.Rates.table());
        lines.add("&7A new address: &ffrom " + P_Economy.ef(C_Economy.addr_purchase_price()) + " &8(offices set their own)");
        lines.add("&8Offices may take up to " + com.vodhanel.minecraft.va_postal.economy.Rates.discount_text_max()
                + " off the postage of mail they send.");
        return CounterMenu.item(Material.PAPER, "&6Postage rates", lines.toArray(new String[0]));
    }

    private static org.bukkit.inventory.ItemStack network() {
        List<String> offices = Clerks.offices();
        int addresses = 0;
        for (String office : offices) {
            String[] a = C_Arrays.addresses_list(office);
            addresses += a == null ? 0 : a.length;
        }
        return CounterMenu.item(Material.FILLED_MAP, "&6The network",
                "&7Post offices here: &f" + offices.size(),
                "&7Addresses: &f" + addresses);
    }

    private static List<String> owned(UUID player) {
        List<String> out = new ArrayList<>();
        for (String office : Clerks.offices()) {
            if (player.equals(C_Owner.get_owner_local_po_id(office))) {
                out.add(office);
            }
        }
        return out;
    }
}
