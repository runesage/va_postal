package com.vodhanel.minecraft.va_postal.config;

import com.vodhanel.minecraft.va_postal.VA_postal;

public class C_Economy {
    VA_postal plugin;

    public C_Economy(VA_postal instance) {
        plugin = instance;
    }

    public static synchronized double postage_price(boolean local) {
        String spath;
        if (local) {
            spath = GetConfig.path_format("economy.postage.letter.local");
        } else {
            spath = GetConfig.path_format("economy.postage.letter.out_town");
        }
        return price(spath);
    }

    public static synchronized double ship_price(boolean local) {
        String spath;
        if (local) {
            spath = GetConfig.path_format("economy.postage.shipment.local");
        } else {
            spath = GetConfig.path_format("economy.postage.shipment.out_town");
        }
        return price(spath);
    }

    public static synchronized double cod_surchg() {
        String spath = GetConfig.path_format("economy.postage.shipment.cod_surchg");
        return price(spath);
    }

    public static synchronized double distr_price() {
        String spath = GetConfig.path_format("economy.postage.distribution");
        return price(spath);
    }

    public static synchronized double po_purchase_price() {
        String spath = GetConfig.path_format("economy.postoffice.purchase_price");
        return price(spath);
    }

    public static synchronized double addr_purchase_price() {
        String spath = GetConfig.path_format("economy.address.purchase_price");
        return price(spath);
    }

    /** A price from the config: 0 if it's missing, not a number, or negative (a negative price would pay the buyer). */
    private static double price(String spath) {
        try {
            double value = Double.parseDouble(VA_postal.plugin.getConfig().getString(spath).trim());
            return Double.isFinite(value) && value > 0.0D ? value : 0.0D;
        } catch (Exception e) {
            return 0.0D;
        }
    }

    private static double config_double(String path, double fallback) {
        try {
            String str = VA_postal.plugin.getConfig().getString(GetConfig.path_format(path));
            return str == null ? fallback : Double.parseDouble(str.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** F: the working balance every office keeps on top of its liabilities. */
    public static synchronized double office_floor() {
        return Math.max(0.0D, config_double("economy.office_floor", 500.0D));
    }

    /** Central keeps its liabilities plus this before releasing surplus. */
    public static synchronized double central_buffer() {
        return Math.max(0.0D, config_double("economy.central_buffer", 5000.0D));
    }

    /** Length of a Postal economy day, in seconds (minimum one minute). */
    public static synchronized long day_seconds() {
        return Math.max(60L, (long) config_double("economy.day_seconds", 86400.0D));
    }

    /** Postage held for mail that's never picked up is refunded after this many Postal days. */
    public static synchronized int hold_expiry_days() {
        return Math.max(1, (int) config_double("economy.postage.hold_expiry_days", 7.0D));
    }

    /** Epoch second the last Postal day ran (0: never). */
    public static synchronized long last_day() {
        return (long) config_double("economy.last_day", 0.0D);
    }

    public static synchronized void set_last_day(long epoch_seconds) {
        VA_postal.plugin.getConfig().set(GetConfig.path_format("economy.last_day"), Long.toString(epoch_seconds));
        VA_postal.plugin.saveConfig();
    }

    private static String config_string(String path, String fallback) {
        try {
            String str = VA_postal.plugin.getConfig().getString(GetConfig.path_format(path));
            return str == null || str.trim().isEmpty() ? fallback : str.trim();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static synchronized double upkeep_base() {
        return Math.max(0.0D, config_double("economy.upkeep.base", 50.0D));
    }

    public static synchronized double upkeep_per_address() {
        return Math.max(0.0D, config_double("economy.upkeep.per_address", 5.0D));
    }

    public static synchronized double upkeep_per_waypoint() {
        return Math.max(0.0D, config_double("economy.upkeep.per_waypoint", 0.0D));
    }

    /** Share of the day's revenue taken as upkeep (an income tax), 0..1. */
    public static synchronized double upkeep_revenue_rate() {
        return Math.min(1.0D, Math.max(0.0D, config_double("economy.upkeep.revenue_rate", 0.0D)));
    }

    /** What counts as work for the dividend: revenue or deliveries. */
    public static synchronized String dividend_basis() {
        String basis = config_string("economy.dividend.basis", "revenue").toLowerCase();
        return "deliveries".equals(basis) ? "deliveries" : "revenue";
    }

    /** k: share of Central's surplus released each day, 0..1. */
    public static synchronized double dividend_release_rate() {
        return Math.min(1.0D, Math.max(0.0D, config_double("economy.dividend.release_rate", 0.5D)));
    }

    /** Per-office dividend cap as a share of its revenue, 0..DailyMath.MAX_CAP (0.45). */
    public static synchronized double dividend_cap() {
        return Math.min(com.vodhanel.minecraft.va_postal.economy.DailyMath.MAX_CAP,
                Math.max(0.0D, config_double("economy.dividend.cap", 0.4D)));
    }

    /** Which account is Central: postal (postal-central) or towny (Towny's closed-economy server account). */
    public static synchronized String central_account() {
        return "towny".equalsIgnoreCase(config_string("economy.central_account", "postal")) ? "towny" : "postal";
    }
}
