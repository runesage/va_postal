package com.vodhanel.minecraft.va_postal.deed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suggests a house name for a new address (docs/design/deeds.md §5), in the spirit of British house names:
 * Rose Cottage, The Old Forge, Ashdene. A name is a shape ("{tree}{ending}") with each {list} filled from a word
 * list; the words and shapes come from names.yml, and the biome around the mailbox picks a setting with its own.
 * Pure: no server needed, so it's unit tested.
 */
public final class HouseNames {
    /** The longest address name: what a sign line holds. */
    public static final int MAX_LENGTH = 15;
    private static final Pattern SLOT = Pattern.compile("\\{([a-z_]+)}");
    private static final int TRIES = 60;

    /** Where the mailbox stands, from its biome. */
    public enum Setting { ARID, SNOWY, WATER, WOODLAND, HILLS, MEADOW, ANY }

    /** Words by list name, the shapes, and each setting's own lists and shapes. */
    public record Words(Map<String, List<String>> words, List<String> shapes,
                        Map<Setting, Map<String, List<String>>> setting_words, Map<Setting, List<String>> setting_shapes) {
    }

    private HouseNames() {
    }

    /** The setting for a biome key such as {@code minecraft:snowy_taiga}. */
    public static Setting setting_of(String biome) {
        // Whole words of the key's path: "mangrove_swamp" mustn't count as the snowy "grove".
        String b = biome == null ? "" : biome.toLowerCase(Locale.ROOT);
        Set<String> parts = Set.of(b.substring(b.indexOf(':') + 1).split("_"));
        if (any(parts, "snowy", "frozen", "ice", "grove", "jagged", "frost")) {
            return Setting.SNOWY;
        }
        if (any(parts, "desert", "badlands", "savanna", "mesa")) {
            return Setting.ARID;
        }
        if (any(parts, "river", "beach", "swamp", "ocean", "mangrove", "shore", "lake")) {
            return Setting.WATER;
        }
        if (any(parts, "forest", "taiga", "jungle", "woods", "birch", "dark", "cherry")) {
            return Setting.WOODLAND;
        }
        if (any(parts, "hills", "mountain", "mountains", "windswept", "stony", "peaks", "slopes", "plateau")) {
            return Setting.HILLS;
        }
        if (any(parts, "plains", "meadow", "flower", "sunflower")) {
            return Setting.MEADOW;
        }
        return Setting.ANY;
    }

    private static boolean any(Set<String> parts, String... words) {
        for (String w : words) {
            if (parts.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A name for {@code setting} that no address in {@code taken} has (compared as stored: lower case, underscores),
     * at most {@link #MAX_LENGTH} long, as stored (lower case, spaces as underscores); null if none could be found.
     */
    public static String suggest(Words w, Setting setting, Set<String> taken, Random rnd) {
        List<String> own_shapes = w.setting_shapes().getOrDefault(setting, List.of());
        Map<String, List<String>> own_words = w.setting_words().getOrDefault(setting, Map.of());
        for (int i = 0; i < TRIES; i++) {
            List<String> shapes = !own_shapes.isEmpty() && (w.shapes().isEmpty() || rnd.nextBoolean()) ? own_shapes : w.shapes();
            if (shapes.isEmpty()) {
                return null;
            }
            String name = fill(shapes.get(rnd.nextInt(shapes.size())), own_words, w.words(), rnd);
            if (name == null) {
                continue;
            }
            name = stored(name);
            if (!name.isEmpty() && name.length() <= MAX_LENGTH && !taken.contains(name)) {
                return name;
            }
        }
        return null;
    }

    /** As an address name is stored: lower case, spaces as underscores, no apostrophes. */
    public static String stored(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replace("'", "").replaceAll("\\s+", "_");
    }

    private static String fill(String shape, Map<String, List<String>> own, Map<String, List<String>> general, Random rnd) {
        Matcher m = SLOT.matcher(shape);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            List<String> list = own.getOrDefault(m.group(1), general.get(m.group(1)));
            if (list == null || list.isEmpty()) {
                return null; // a shape naming a list that isn't there
            }
            m.appendReplacement(out, Matcher.quoteReplacement(list.get(rnd.nextInt(list.size()))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The words of names.yml, from its parsed sections (plain maps and lists, as a YAML loader gives them). */
    @SuppressWarnings("unchecked")
    public static Words words(Map<String, Object> words, List<String> shapes, Map<String, Object> settings) {
        Map<Setting, Map<String, List<String>>> sw = new java.util.EnumMap<>(Setting.class);
        Map<Setting, List<String>> ss = new java.util.EnumMap<>(Setting.class);
        if (settings != null) {
            for (Map.Entry<String, Object> e : settings.entrySet()) {
                Setting s;
                try {
                    s = Setting.valueOf(e.getKey().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    continue;
                }
                if (e.getValue() instanceof Map<?, ?> m) {
                    if (m.get("words") instanceof Map<?, ?> lists) {
                        sw.put(s, lists_of((Map<String, Object>) lists));
                    }
                    if (m.get("shapes") instanceof List<?> l) {
                        ss.put(s, strings(l));
                    }
                }
            }
        }
        return new Words(words == null ? Map.of() : lists_of(words), shapes == null ? List.of() : List.copyOf(shapes), sw, ss);
    }

    private static Map<String, List<String>> lists_of(Map<String, Object> m) {
        Map<String, List<String>> out = new java.util.HashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getValue() instanceof List<?> l) {
                out.put(e.getKey(), strings(l));
            }
        }
        return out;
    }

    private static List<String> strings(List<?> l) {
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
