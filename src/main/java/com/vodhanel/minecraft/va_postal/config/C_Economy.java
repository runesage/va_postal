package com.vodhanel.minecraft.va_postal.config;

import com.vodhanel.minecraft.va_postal.VA_postal;

public class C_Economy {
    VA_postal plugin;

    public C_Economy(VA_postal instance) {
        plugin = instance;
    }

    public static synchronized double postage_price(boolean local) {
        double result = 0.0D;
        String spath;
        if (local) {
            spath = GetConfig.path_format("economy.postage.letter.local");
        } else {
            spath = GetConfig.path_format("economy.postage.letter.out_town");
        }
        try {
            String str = VA_postal.plugin.getConfig().getString(spath);
            result = Double.parseDouble(str);
        } catch (Exception e) {
            return 0.0D;
        }
        return result;
    }

    public static synchronized double ship_price(boolean local) {
        double result = 0.0D;
        String spath;
        if (local) {
            spath = GetConfig.path_format("economy.postage.shipment.local");
        } else {
            spath = GetConfig.path_format("economy.postage.shipment.out_town");
        }
        try {
            String str = VA_postal.plugin.getConfig().getString(spath);
            result = Double.parseDouble(str);
        } catch (Exception e) {
            return 0.0D;
        }
        return result;
    }

    public static synchronized double cod_surchg() {
        double result = 0.0D;

        String spath = GetConfig.path_format("economy.postage.shipment.cod_surchg");
        try {
            String str = VA_postal.plugin.getConfig().getString(spath);
            result = Double.parseDouble(str);
        } catch (Exception e) {
            return 0.0D;
        }
        return result;
    }

    public static synchronized double distr_price() {
        double result = 0.0D;
        String spath = GetConfig.path_format("economy.postage.distribution");
        try {
            String str = VA_postal.plugin.getConfig().getString(spath);
            result = Double.parseDouble(str);
        } catch (Exception e) {
            return 0.0D;
        }
        return result;
    }

    public static synchronized double po_purchase_price() {
        double result = 0.0D;
        String spath = GetConfig.path_format("economy.postoffice.purchase_price");
        try {
            String str = VA_postal.plugin.getConfig().getString(spath);
            result = Double.parseDouble(str);
        } catch (Exception e) {
            return 0.0D;
        }
        return result;
    }

    public static synchronized double addr_purchase_price() {
        double result = 0.0D;
        String spath = GetConfig.path_format("economy.address.purchase_price");
        try {
            String str = VA_postal.plugin.getConfig().getString(spath);
            result = Double.parseDouble(str);
        } catch (Exception e) {
            return 0.0D;
        }
        return result;
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

    /** Epoch second the last Postal day ran (0: never). */
    public static synchronized long last_day() {
        return (long) config_double("economy.last_day", 0.0D);
    }

    public static synchronized void set_last_day(long epoch_seconds) {
        VA_postal.plugin.getConfig().set(GetConfig.path_format("economy.last_day"), Long.toString(epoch_seconds));
        VA_postal.plugin.saveConfig();
    }
}
