package com.vodhanel.minecraft.va_postal.deed;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.store.Deed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The document a deed's holder carries (docs/design/deeds.md §4): a signed book worded from deeds.yml, with the
 * deed's id and issue in its PersistentDataContainer. The text is for reading; the id and issue are what count,
 * checked against the deed's record when it's used.
 */
public final class DeedDocument {
    /** The deed an item stands for: its id and which issue of the document it is. */
    public record Ref(UUID id, int issue) {
    }

    private static NamespacedKey id_key;
    private static NamespacedKey issue_key;
    private static YamlConfiguration template;

    private DeedDocument() {
    }

    private static NamespacedKey id_key() {
        if (id_key == null) {
            id_key = new NamespacedKey(VA_postal.plugin, "deed_id");
            issue_key = new NamespacedKey(VA_postal.plugin, "deed_issue");
        }
        return id_key;
    }

    /** The deed an item stands for, or null if it isn't a deed. */
    public static Ref read(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(id_key(), PersistentDataType.STRING);
        Integer issue = pdc.get(issue_key, PersistentDataType.INTEGER);
        if (id == null || issue == null) {
            return null;
        }
        try {
            return new Ref(UUID.fromString(id), issue);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The document for {@code deed}, worded from deeds.yml. */
    public static ItemStack create(Deed deed, String world, String server) {
        ConfigurationSection t = template().getConfigurationSection(deed.kind().name().toLowerCase(java.util.Locale.ROOT));
        Map<String, String> values = values(deed, world, server);
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        String title = fill(t == null ? "Deed" : t.getString("title", "Deed"), values);
        meta.title(Text.component(Text.strip(Text.color(title.length() > 32 ? title.substring(0, 32) : title))));
        meta.author(Text.component(Text.strip(Text.color(fill(t == null ? "{postmaster}" : t.getString("author", "{postmaster}"), values)))));
        List<net.kyori.adventure.text.Component> pages = new ArrayList<>();
        for (String page : t == null ? List.<String>of() : t.getStringList("pages")) {
            pages.add(Text.component(Text.color(fill(page, values))));
        }
        meta.pages(pages);
        if (t != null && t.getString("name") != null) {
            meta.customName(Text.component(Text.color(fill(t.getString("name"), values))));
        }
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(id_key(), PersistentDataType.STRING, deed.id().toString());
        meta.getPersistentDataContainer().set(issue_key, PersistentDataType.INTEGER, deed.issue());
        item.setItemMeta(meta);
        return item;
    }

    /** The placeholders' values for {@code deed}. */
    static Map<String, String> values(Deed deed, String world, String server) {
        SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy");
        String office = display(deed.office());
        return Map.of(
                "{address}", display(deed.name()),
                "{office}", office,
                "{world}", world == null ? "the realm" : world,
                "{server}", server == null ? "this server" : server,
                "{holder}", deed.holder_name(),
                "{date}", date.format(new Date(deed.created_at())),
                "{expires}", date.format(new Date(deed.expires_at())),
                "{deed_id}", deed.id().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT),
                "{postmaster}", "Postmaster of " + office,
                "{price}", !VA_postal.economy_configured ? String.format("%.2f", deed.price())
                        : com.vodhanel.minecraft.va_postal.common.P_Economy.ef(deed.price()));
    }

    /** A stored name as people read it: "rose_cottage" -> "Rose Cottage". */
    public static String display(String stored) {
        StringBuilder out = new StringBuilder();
        for (String part : stored.split("_")) {
            if (!part.isEmpty()) {
                out.append(out.length() == 0 ? "" : " ").append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return out.toString();
    }

    static String fill(String text, Map<String, String> values) {
        String out = text;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    /** deeds.yml from the plugin's folder (written there from the jar the first time), over the bundled one. */
    static synchronized YamlConfiguration template() {
        if (template == null) {
            template = load("deeds.yml");
        }
        return template;
    }

    /** {@code name} from the plugin's folder, written there from the jar if it isn't yet; the jar's copy fills gaps. */
    static YamlConfiguration load(String name) {
        File file = new File(VA_postal.plugin.getDataFolder(), name);
        if (!file.exists()) {
            VA_postal.plugin.saveResource(name, false);
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        java.io.InputStream bundled = VA_postal.plugin.getResource(name);
        if (bundled != null) {
            y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
        }
        return y;
    }

    /** Forgets the loaded templates, so edits are read again. */
    public static synchronized void reload() {
        template = null;
    }
}
