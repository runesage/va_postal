package com.vodhanel.minecraft.va_postal.economy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * What the daily cycle needs to remember (docs/design/economy.md §6, §8, §14), kept in
 * {@code plugins/Postal/economy.yml}:
 * <ul>
 *   <li>today's activity per office: revenue (its shares of postage, shipping and COD surcharges) and
 *       deliveries;</li>
 *   <li>today's money flows by kind (the flow log);</li>
 *   <li>the dividend carry-over and each office's upkeep arrears;</li>
 *   <li>postage held in escrow for mail not yet delivered ({@link Hold});</li>
 *   <li>the last {@link #HISTORY_DAYS} closed days.</li>
 * </ul>
 * Saved when a day closes, on shutdown and, if something changed, every few minutes.
 */
public final class EconomyState {
    public static final int HISTORY_DAYS = 30;

    /** Money flow kinds. "In"/"out" are between players and Postal; "internal" moves between Postal's accounts. */
    public enum Flow {
        POSTAGE("in"), SHIPPING("in"), COD_SURCHARGE("in"), DISTRIBUTION("in"), OFFICE_PURCHASE("in"),
        ADDRESS_PURCHASE("in"), DEPOSIT("in"),
        REFUND("out"), WITHDRAWAL("out"), POSTAGE_REFUND("out"),
        UPKEEP("internal"), SWEEP("internal"), SEED("internal"), DIVIDEND("internal");

        public final String direction;

        Flow(String direction) {
            this.direction = direction;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static File file;
    private static Logger log = Logger.getLogger("Postal");
    private static boolean dirty;

    private static final Map<String, Double> revenue = new TreeMap<>();
    private static final Map<String, Integer> deliveries = new TreeMap<>();
    private static final Map<Flow, Double> flows = new LinkedHashMap<>();
    private static final Map<String, Double> arrears = new TreeMap<>();
    private static double carry;
    private static long day_started;
    private static final List<Map<String, Object>> history = new ArrayList<>();
    private static final Map<String, Hold> holds = new LinkedHashMap<>();

    private EconomyState() {
    }

    // ---- Load / save -----------------------------------------------------------------------

    public static synchronized void load(File data_folder, Logger logger) {
        log = logger;
        file = new File(data_folder, "economy.yml");
        clear();
        if (!file.exists()) {
            day_started = now();
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        read_doubles(y.getConfigurationSection("today.revenue"), revenue);
        ConfigurationSection d = y.getConfigurationSection("today.deliveries");
        if (d != null) {
            for (String k : d.getKeys(false)) {
                deliveries.put(k, d.getInt(k));
            }
        }
        ConfigurationSection f = y.getConfigurationSection("today.flows");
        if (f != null) {
            for (Flow flow : Flow.values()) {
                double v = f.getDouble(flow.key(), 0.0D);
                if (v != 0.0D) {
                    flows.put(flow, v);
                }
            }
        }
        read_doubles(y.getConfigurationSection("arrears"), arrears);
        carry = y.getDouble("carry", 0.0D);
        day_started = y.getLong("today.started", now());
        ConfigurationSection h = y.getConfigurationSection("holds");
        if (h != null) {
            for (String k : h.getKeys(false)) {
                ConfigurationSection hs = h.getConfigurationSection(k);
                Hold hold = hs == null ? null : Hold.from_map(k, hs.getValues(false));
                if (hold != null) {
                    holds.put(k, hold);
                }
            }
        }
        for (Map<?, ?> m : y.getMapList("history")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                copy.put(String.valueOf(e.getKey()), e.getValue());
            }
            history.add(copy);
        }
    }

    public static synchronized void save() {
        if (file == null) {
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("today.started", day_started);
        for (Map.Entry<String, Double> e : revenue.entrySet()) {
            y.set("today.revenue." + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Integer> e : deliveries.entrySet()) {
            y.set("today.deliveries." + e.getKey(), e.getValue());
        }
        for (Map.Entry<Flow, Double> e : flows.entrySet()) {
            y.set("today.flows." + e.getKey().key(), e.getValue());
        }
        for (Map.Entry<String, Double> e : arrears.entrySet()) {
            y.set("arrears." + e.getKey(), e.getValue());
        }
        y.set("carry", carry);
        for (Hold hold : holds.values()) {
            y.set("holds." + hold.id, hold.to_map());
        }
        y.set("history", history);
        try {
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            log.warning("Could not save " + file + ": " + e);
        }
    }

    public static synchronized void save_if_dirty() {
        if (dirty) {
            save();
        }
    }

    // ---- Recording -------------------------------------------------------------------------

    public static synchronized void record(Flow flow, double amount) {
        if (amount > 0.0D) {
            flows.merge(flow, amount, Double::sum);
            dirty = true;
        }
    }

    /** An office's share of postage, shipping or a COD surcharge: counts as its revenue today. */
    public static synchronized void add_revenue(String office, double amount) {
        if (office != null && amount > 0.0D) {
            revenue.merge(key(office), amount, Double::sum);
            dirty = true;
        }
    }

    public static synchronized void add_delivery(String office) {
        if (office != null) {
            deliveries.merge(key(office), 1, Integer::sum);
            dirty = true;
        }
    }

    // ---- Postage holds ---------------------------------------------------------------------

    public static synchronized void put_hold(Hold hold) {
        holds.put(hold.id, hold);
        dirty = true;
    }

    public static synchronized Hold hold(String id) {
        return id == null ? null : holds.get(id);
    }

    public static synchronized Hold remove_hold(String id) {
        Hold hold = id == null ? null : holds.remove(id);
        if (hold != null) {
            dirty = true;
        }
        return hold;
    }

    /** Marks a hold changed (after editing its fields), so it's saved. */
    public static synchronized void touch_hold() {
        dirty = true;
    }

    public static synchronized List<Hold> holds() {
        return new ArrayList<>(holds.values());
    }

    /** All postage held in escrow: Central owes it back until the mail is delivered. */
    public static synchronized double held_total() {
        double total = 0.0D;
        for (Hold hold : holds.values()) {
            total += hold.total();
        }
        return total;
    }

    // ---- Reading ---------------------------------------------------------------------------

    public static synchronized double revenue(String office) {
        return revenue.getOrDefault(key(office), 0.0D);
    }

    public static synchronized int deliveries(String office) {
        return deliveries.getOrDefault(key(office), 0);
    }

    public static synchronized double arrears(String office) {
        return arrears.getOrDefault(key(office), 0.0D);
    }

    public static synchronized void set_arrears(String office, double amount) {
        if (amount > 0.005D) {
            arrears.put(key(office), round(amount));
        } else {
            arrears.remove(key(office));
        }
        dirty = true;
    }

    public static synchronized double carry() {
        return carry;
    }

    public static synchronized void set_carry(double amount) {
        carry = Math.max(0.0D, round(amount));
        dirty = true;
    }

    public static synchronized Map<Flow, Double> flows_today() {
        return new LinkedHashMap<>(flows);
    }

    /** Closed days, newest first. */
    public static synchronized List<Map<String, Object>> history() {
        List<Map<String, Object>> copy = new ArrayList<>(history);
        Collections.reverse(copy);
        return copy;
    }

    /**
     * Closes today: appends a history entry (today's flows plus {@code summary}), keeps the last
     * {@link #HISTORY_DAYS}, clears today's counters and saves.
     */
    public static synchronized void close_day(Map<String, Object> summary) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("started", day_started);
        entry.put("closed", now());
        for (Flow flow : Flow.values()) {
            Double v = flows.get(flow);
            if (v != null && v != 0.0D) {
                entry.put(flow.key(), round(v));
            }
        }
        entry.putAll(summary);
        history.add(entry);
        while (history.size() > HISTORY_DAYS) {
            history.remove(0);
        }
        revenue.clear();
        deliveries.clear();
        flows.clear();
        day_started = now();
        save();
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private static void clear() {
        revenue.clear();
        deliveries.clear();
        flows.clear();
        arrears.clear();
        history.clear();
        holds.clear();
        carry = 0.0D;
        dirty = false;
    }

    private static void read_doubles(ConfigurationSection cs, Map<String, Double> into) {
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                into.put(k, cs.getDouble(k));
            }
        }
    }

    static String key(String office) {
        return office == null ? "" : office.toLowerCase(Locale.ROOT).trim();
    }

    static double round(double amount) {
        return Math.round(amount * 100.0D) / 100.0D;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }
}
