package com.vodhanel.minecraft.va_postal.common;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Text helpers in place of Bukkit's deprecated string-text API (ChatColor, String sign lines and book pages,
 * getDisplayName). Postal keeps its text as legacy strings with § codes: its books and signs are parsed back by
 * line and colour prefix. These convert at the edges so that every string reads back exactly as before.
 */
public final class Text {
    private static final char SECTION_CHAR = LegacyComponentSerializer.SECTION_CHAR;
    /** § codes, hex colours as §x§r§r§g§g§b§b: Paper's own legacy format. */
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character(SECTION_CHAR).hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final String CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx";
    private static final Pattern STRIP = Pattern.compile("(?i)" + SECTION_CHAR + "[0-9A-FK-ORX]");
    /** The named colours in code order, §0 to §f. */
    private static final List<NamedTextColor> COLORS = List.of(NamedTextColor.BLACK, NamedTextColor.DARK_BLUE,
            NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA, NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE,
            NamedTextColor.GOLD, NamedTextColor.GRAY, NamedTextColor.DARK_GRAY, NamedTextColor.BLUE, NamedTextColor.GREEN,
            NamedTextColor.AQUA, NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.WHITE);
    /** Decorations in the order Bukkit writes them, with their codes. */
    private static final TextDecoration[] DECORATIONS = {TextDecoration.BOLD, TextDecoration.ITALIC,
            TextDecoration.UNDERLINED, TextDecoration.STRIKETHROUGH, TextDecoration.OBFUSCATED};
    private static final char[] DECORATION_CODES = {'l', 'o', 'n', 'm', 'k'};

    private Text() {
    }

    /** A legacy (§) string as a component; null reads as empty. */
    public static Component component(String legacy) {
        return legacy == null || legacy.isEmpty() ? Component.empty() : SECTION.deserialize(legacy);
    }

    /**
     * A component as a legacy (§) string, the way Paper's deprecated String getters (BookMeta#getPage, Sign#getLine,
     * Player#getDisplayName) write it with Bukkit's CraftChatMessage.fromComponent: every piece of text with a colour
     * gets its code again, even the same colour as the piece before. Adventure's own legacy serializer leaves repeats
     * out ("§7a\n§7b" reads back "§7a\nb"), which would shift the fixed columns Postal parses out of page 1 of a letter.
     */
    public static String legacy(Component component) {
        StringBuilder out = new StringBuilder();
        if (component != null) {
            write(component, Style.empty(), out, new boolean[]{false});
        }
        return out.toString();
    }

    private static void write(Component c, Style parent, StringBuilder out, boolean[] formatted) {
        Style style = c.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        String content = c instanceof TextComponent text ? text.content() : "";
        TextColor color = style.color();
        if (!content.isEmpty() || color != null) {
            if (color != null) {
                int named = COLORS.indexOf(NamedTextColor.namedColor(color.value()));
                if (named >= 0) {
                    out.append(SECTION_CHAR).append(Character.forDigit(named, 16));
                } else {
                    out.append(SECTION_CHAR).append('x');
                    for (char ch : String.format("%06x", color.value()).toCharArray()) {
                        out.append(SECTION_CHAR).append(ch);
                    }
                }
                formatted[0] = true;
            } else if (formatted[0]) {
                out.append(SECTION_CHAR).append('r');
                formatted[0] = false;
            }
        }
        for (int i = 0; i < DECORATIONS.length; i++) {
            if (style.decoration(DECORATIONS[i]) == TextDecoration.State.TRUE) {
                out.append(SECTION_CHAR).append(DECORATION_CODES[i]);
                formatted[0] = true;
            }
        }
        out.append(content);
        for (Component child : c.children()) {
            write(child, style, out, formatted);
        }
    }

    /** Line {@code i} of the sign's front, as Sign#getLine read it. */
    public static String line(org.bukkit.block.Sign sign, int i) {
        return legacy(sign.getSide(org.bukkit.block.sign.Side.FRONT).line(i));
    }

    /** Sets line {@code i} of the sign's front, as Sign#setLine did. */
    public static void line(org.bukkit.block.Sign sign, int i, String text) {
        sign.getSide(org.bukkit.block.sign.Side.FRONT).line(i, component(text));
    }

    /** {@code &} colour codes to {@code §} ones, exactly as ChatColor.translateAlternateColorCodes('&', s). */
    public static String color(String s) {
        if (s == null) {
            return null;
        }
        char[] b = s.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&' && CODES.indexOf(b[i + 1]) > -1) {
                b[i] = SECTION_CHAR;
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }

    /** The text without § codes, exactly as ChatColor.stripColor(s). */
    public static String strip(String s) {
        return s == null ? null : STRIP.matcher(s).replaceAll("");
    }
}
