package com.vodhanel.minecraft.va_postal.mail;

import java.util.Locale;

/**
 * The COD line on a shipping label (page 1, line 12). It stores a plain amount, never the economy's
 * formatted one: v4 wrote "[COD] $" + format(price), i.e. "[COD] $$12.00" with EssentialsX, which
 * then parsed back as 0, so every COD parcel was accepted for free.
 */
public final class CodLabel {
    static final String MARKER = "[COD]";
    private static final String PREFIX = "§c  " + MARKER + " ";

    private CodLabel() {
    }

    public static String line(double amount) {
        return PREFIX + String.format(Locale.ROOT, "%.2f", amount);
    }

    /** The amount on a COD line, or 0 if the line isn't one (or holds no number). */
    public static double amount(String line) {
        if (line == null) {
            return 0.0D;
        }
        int at = line.indexOf(MARKER);
        if (at < 0) {
            return 0.0D;
        }
        String digits = line.substring(at + MARKER.length()).replaceAll("[^0-9.]", "");
        try {
            return digits.isEmpty() ? 0.0D : Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            return 0.0D;
        }
    }
}
