package com.vodhanel.minecraft.va_postal.common;


public class MC_format {
    public MC_format() {
    }

    public enum ColourUtils {
        BLACK("&0", "§0"),
        DARK_BLUE("&1", "§1"),
        DARK_GREEN("&2", "§2"),
        DARK_AQUA("&3", "§3"),
        DARK_RED("&4", "§4"),
        DARK_PURPLE("&5", "§5"),
        GOLD("&6", "§6"),
        GRAY("&7", "§7"),
        DARK_GRAY("&8", "§8"),
        BLUE("&9", "§9"),
        GREEN("&a", "§a"),
        AQUA("&b", "§b"),
        RED("&c", "§c"),
        LIGHT_PURPLE("&d", "§d"),
        YELLOW("&e", "§e"),
        WHITE("&f", "§f"),
        MAGIC("&k", "§k"),
        BOLD("&l", "§l"),
        STRIKETHROUGH("&m", "§m"),
        UNDERLINE("&n", "§n"),
        ITALIC("&o", "§o"),
        RESET("&r", "§r");

        private final String input;
        private final String MinecraftColor;

        ColourUtils(String input, String MinecraftColor) {
            this.input = input;
            this.MinecraftColor = MinecraftColor;
        }

        public static String format(String message) {
            String msg = message;
            for (ColourUtils c : values()) {
                msg = msg.replace(c.getInput(), c.getMinecraftColor());
            }
            return msg;
        }

        public String getMinecraftColor() {
            return MinecraftColor;
        }

        public String getInput() {
            return input;
        }
    }
}
