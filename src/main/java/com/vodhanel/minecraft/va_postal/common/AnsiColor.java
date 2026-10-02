package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;

public class AnsiColor {
    VA_postal plugin;
    public static final String RESET = "\u001b[0;37m";
    public static final String BLACK = "\u001b[0;30m";
    public static final String RED = "\u001b[1;31m";
    public static final String GREEN = "\u001b[0;32m";
    public static final String L_GREEN = "\u001b[1;32m";
    public static final String YELLOW = "\u001b[0;33m";
    public static final String L_YELLOW = "\u001b[1;33m";
    public static final String BLUE = "\u001b[1;34m";
    public static final String MAGENTA = "\u001b[1;35m";
    public static final String CYAN = "\u001b[0;36m";
    public static final String L_CYAN = "\u001b[1;36m";
    public static final String WHITE = "\u001b[0;37m";
    public static final String L_WHITE = "\u001b[1;37m";

    public AnsiColor(VA_postal instance) {
        this.plugin = instance;
    }
}
