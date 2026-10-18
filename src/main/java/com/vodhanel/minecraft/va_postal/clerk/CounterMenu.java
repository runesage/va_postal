package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.deed.Deeds;
import com.vodhanel.minecraft.va_postal.store.Deed;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * An office's counter (docs/design/deeds.md §2): a chest-sized menu the clerk opens. Everyone sees <i>Buy an
 * address</i>, <i>Office info</i> and, with deeds of this office still to use, <i>Replace a lost deed</i>; the owner
 * (and admins) also see <i>Address price</i>.
 */
public final class CounterMenu implements InventoryHolder {
    static final int BUY = 11;
    static final int INFO = 13;
    static final int DEEDS = 15;
    static final int PRICE = 22;

    private final String office;
    private final boolean manager;
    private final Inventory inventory;

    private CounterMenu(Player player, String office) {
        this.office = office;
        this.manager = manages(player, office);
        this.inventory = Bukkit.createInventory(this, 27, Text.component(Text.color("&8" + DeedDocument.display(office) + " Post Office")));
        inventory.setItem(BUY, item(Material.WRITABLE_BOOK, "&6Buy an address",
                "&7Name a house and take its deed", "&7to the chest that will be", "&7your mailbox.", "",
                "&7Price: &f" + Clerks.price_text(office)));
        inventory.setItem(INFO, info());
        List<Deed> open = open_deeds(player);
        if (!open.isEmpty()) {
            List<String> lore = new ArrayList<>();
            lore.add("&7A new copy of your deed;");
            lore.add("&7older copies stop working.");
            lore.add("");
            SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy");
            for (Deed d : open) {
                lore.add("&f" + DeedDocument.display(d.name()) + " &7until " + date.format(new Date(d.expires_at())));
            }
            inventory.setItem(DEEDS, item(Material.BOOK, "&6Replace a lost deed", lore.toArray(new String[0])));
        }
        if (manager) {
            inventory.setItem(PRICE, item(Material.GOLD_INGOT, "&eAddress price",
                    "&7Now: &f" + Clerks.price_text(office) + (Clerks.has_price(office) ? "" : " &8(server default)"),
                    "", "&7Click to change it."));
        }
    }

    /** Opens the counter of {@code office} for {@code player}. */
    public static void open(Player player, String office) {
        player.openInventory(new CounterMenu(player, office).inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    String office() {
        return office;
    }

    /** A click on slot {@code slot} of this menu. */
    void click(Player player, int slot) {
        switch (slot) {
            case BUY -> {
                player.closeInventory();
                BuyForm.open(player, office, null, null);
            }
            case DEEDS -> {
                player.closeInventory();
                replace(player);
            }
            case PRICE -> {
                if (manager) {
                    player.closeInventory();
                    price_form(player);
                }
            }
            default -> {
            }
        }
    }

    private ItemStack info() {
        String owner = C_Owner.get_owner_local_po_name(office);
        String[] addresses = C_Arrays.addresses_list(office);
        return item(Material.MAP, "&6" + DeedDocument.display(office) + " Post Office",
                "&7Postmaster: &f" + (owner == null || owner.isBlank() || "null".equals(owner) ? "the server" : owner),
                "&7Addresses: &f" + (addresses == null ? 0 : addresses.length),
                "&7A new address: &f" + Clerks.price_text(office));
    }

    private List<Deed> open_deeds(Player player) {
        MailStore store = MailStores.active();
        List<Deed> out = new ArrayList<>();
        if (store == null) {
            return out;
        }
        try {
            for (Deed d : store.deeds_of(player.getUniqueId())) {
                if (d.state() == Deed.State.ISSUED && d.kind() == Deed.Kind.ADDRESS && office.equals(d.office())
                        && d.expires_at() > System.currentTimeMillis()) {
                    out.add(d);
                }
            }
        } catch (StoreException e) {
            // the counter opens without the deeds
        }
        return out;
    }

    private void replace(Player player) {
        List<Deed> open = open_deeds(player);
        if (open.isEmpty()) {
            Clerks.say(office, player, "I've no deeds of yours to copy.");
            return;
        }
        for (Deed d : open) {
            Deeds.Result r = Deeds.reissue(d.id());
            Clerks.say(office, player, r.ok() ? "Here's a fresh copy of the deed for " + DeedDocument.display(d.name()) + "."
                    : "I couldn't copy the deed for " + DeedDocument.display(d.name()) + ": " + r.problem() + ".");
        }
    }

    private void price_form(Player player) {
        String now = String.format(java.util.Locale.ROOT, "%.2f", Clerks.price(office));
        List<String> body = List.of("What should a new address at " + DeedDocument.display(office) + " cost?",
                "&7The server's price is " + P_Economy.ef(com.vodhanel.minecraft.va_postal.config.C_Economy.addr_purchase_price())
                        + ". Leave it empty, or use Default, to go back to it.");
        Forms.show(player, new Forms.Form("Address price", body, "Price", now, 16, List.of(
                new Forms.Choice("Set price", "set", (p, value) -> set_price(p, value)),
                new Forms.Choice("Default", "default", (p, value) -> set_price(p, "")))));
    }

    private void set_price(Player player, String value) {
        if (!manages(player, office)) {
            return;
        }
        if (value == null || value.isBlank()) {
            Clerks.set_price(office, null);
            Clerks.say(office, player, "Addresses here cost the server's price again: " + Clerks.price_text(office) + ".");
            return;
        }
        Double price = Clerks.parse_price(value);
        if (price == null) {
            Clerks.say(office, player, "\"" + value + "\" isn't a price I can use.");
            return;
        }
        Clerks.set_price(office, price);
        Clerks.say(office, player, "Very good: a new address here now costs " + Clerks.price_text(office) + ".");
    }

    /** True for the office's owner and admins. */
    static boolean manages(Player player, String office) {
        if (player.hasPermission("postal.admin")) {
            return true;
        }
        UUID owner = C_Owner.get_owner_local_po_id(office);
        return owner != null && owner.equals(player.getUniqueId());
    }

    static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.component(Text.color(name)).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(Text.component(Text.color(l)).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }
}
