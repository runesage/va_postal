package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Central's counter (docs/design/central-office.md §2), opened by the Postmaster General: the network's postage
 * rates, the network at a glance, and the offices the player owns. Charters and office sales join it later.
 */
final class CentralMenu implements Menu {
    static final int RATES = 11;
    static final int NETWORK = 13;
    static final int OFFICES = 15;

    private final Inventory inventory;

    private CentralMenu(Player player) {
        this.inventory = Bukkit.createInventory(this, 27, Text.component(Text.color("&8Central Post Office")));
        inventory.setItem(RATES, rates());
        inventory.setItem(NETWORK, network());
        List<String> owned = owned(player.getUniqueId());
        if (!owned.isEmpty()) {
            List<String> lore = new ArrayList<>();
            for (String office : owned) {
                lore.add("&f" + DeedDocument.display(office));
            }
            lore.add("");
            lore.add("&7Their accounts: /postal office");
            inventory.setItem(OFFICES, CounterMenu.item(Material.CHEST, "&6Your post offices", lore.toArray(new String[0])));
        }
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
        // The counter's cards are for reading; its services (charters, sales) come with their own steps.
    }

    private static org.bukkit.inventory.ItemStack rates() {
        if (!VA_postal.economy_configured) {
            return CounterMenu.item(Material.PAPER, "&6Postage rates", "&7Postage is free on this server.");
        }
        return CounterMenu.item(Material.PAPER, "&6Postage rates",
                "&7A letter, within an office: &f" + P_Economy.ef(C_Economy.postage_price(true)),
                "&7A letter, to another office: &f" + P_Economy.ef(C_Economy.postage_price(false)),
                "&7A letter, to another server: &f" + P_Economy.ef(C_Economy.network_postage_price()),
                "&7A parcel, within an office: &f" + P_Economy.ef(C_Economy.ship_price(true)),
                "&7A parcel, to another office: &f" + P_Economy.ef(C_Economy.ship_price(false)),
                "&7Cash on delivery: &f+" + P_Economy.ef(C_Economy.cod_surchg()),
                "&7A new address: &ffrom " + P_Economy.ef(C_Economy.addr_purchase_price()) + " &8(offices set their own)");
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
