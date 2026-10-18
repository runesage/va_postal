package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.deed.Applications;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.deed.Deeds;
import com.vodhanel.minecraft.va_postal.store.Deed;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The owner's view of an office's address applications (docs/design/deeds.md §3.2): one slip per application;
 * left-click approves it, right-click declines it (refunded in full). The last slot goes back to the counter.
 */
final class ApplicationsMenu implements Menu {
    static final int SIZE = 54;
    static final int BACK = SIZE - 1;

    private final String office;
    private final List<Deed> shown;
    private final Inventory inventory;

    private ApplicationsMenu(String office) {
        this.office = office;
        List<Deed> waiting = Applications.pending(office);
        this.shown = waiting.subList(0, Math.min(waiting.size(), SIZE - 1));
        this.inventory = Bukkit.createInventory(this, SIZE,
                Text.component(Text.color("&8Applications: " + DeedDocument.display(office))));
        SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy");
        for (int i = 0; i < shown.size(); i++) {
            Deed d = shown.get(i);
            inventory.setItem(i, CounterMenu.item(Material.PAPER, "&f" + DeedDocument.display(d.name()),
                    "&7From &f" + d.holder_name(),
                    d.price() > 0.0D && VA_postal.economy_configured ? "&7Paid &f" + P_Economy.ef(d.price()) : "&7Free",
                    "&7Applied " + date.format(new Date(d.created_at())),
                    "&7Lapses " + date.format(new Date(d.expires_at())) + " if unanswered",
                    "", "&aLeft-click: approve", "&cRight-click: decline &7(refunded in full)"));
        }
        inventory.setItem(BACK, CounterMenu.item(Material.ARROW, "&7Back to the counter"));
    }

    static void open(Player player, String office) {
        player.openInventory(new ApplicationsMenu(office).inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void click(Player player, int slot, boolean right) {
        if (slot == BACK) {
            CounterMenu.open(player, office);
            return;
        }
        if (slot < 0 || slot >= shown.size() || !CounterMenu.manages(player, office)) {
            return;
        }
        Deed d = shown.get(slot);
        Deeds.Result r = right ? Applications.deny(d.id(), player.getName(), null) : Applications.approve(d.id(), player.getName());
        Clerks.say(office, player, r.ok()
                ? (right ? "I'll let " + d.holder_name() + " know: " + DeedDocument.display(d.name()) + " is declined, and refunded."
                        : "Very good: " + DeedDocument.display(d.name()) + " is approved for " + d.holder_name() + ".")
                : DeedDocument.display(d.name()) + ": " + r.problem() + ".");
        open(player, office);
    }
}
