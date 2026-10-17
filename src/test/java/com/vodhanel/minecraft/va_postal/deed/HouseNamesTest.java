package com.vodhanel.minecraft.va_postal.deed;

import com.vodhanel.minecraft.va_postal.deed.HouseNames.Setting;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HouseNamesTest {
    @SuppressWarnings("unchecked")
    static HouseNames.Words bundled() {
        try (InputStream in = HouseNamesTest.class.getResourceAsStream("/names.yml")) {
            Map<String, Object> y = new Yaml().load(in);
            return HouseNames.words((Map<String, Object>) y.get("words"), (List<String>) y.get("shapes"),
                    (Map<String, Object>) y.get("settings"));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void suggestionsFitASignAndAreStoredForm() {
        HouseNames.Words w = bundled();
        Random rnd = new Random(1);
        for (Setting s : Setting.values()) {
            for (int i = 0; i < 300; i++) {
                String name = HouseNames.suggest(w, s, Set.of(), rnd);
                assertNotNull(name, s.name());
                assertTrue(name.length() <= HouseNames.MAX_LENGTH, name);
                assertEquals(name.toLowerCase(), name);
                assertFalse(name.contains(" ") || name.contains("'") || name.contains("{"), name);
            }
        }
    }

    @Test
    void neverSuggestsANameTheOfficeHas() {
        HouseNames.Words w = bundled();
        Random rnd = new Random(2);
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String name = HouseNames.suggest(w, Setting.ANY, taken, rnd);
            if (name == null) {
                break; // the lists ran out: fine, as long as nothing repeated
            }
            assertTrue(taken.add(name), "repeated " + name);
        }
        assertTrue(taken.size() > 100, "only " + taken.size() + " distinct names");
    }

    @Test
    void givesUpWhenEveryNameIsTaken() {
        HouseNames.Words w = HouseNames.words(Map.of("tree", List.of("Oak")), List.of("{tree} House"), Map.of());
        assertEquals("oak_house", HouseNames.suggest(w, Setting.ANY, Set.of(), new Random(3)));
        assertNull(HouseNames.suggest(w, Setting.ANY, Set.of("oak_house"), new Random(3)));
    }

    @Test
    void theSettingFlavoursTheName() {
        HouseNames.Words w = bundled();
        Random rnd = new Random(4);
        Set<String> snowy = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            snowy.add(HouseNames.suggest(w, Setting.SNOWY, Set.of(), rnd));
        }
        assertTrue(snowy.stream().anyMatch(n -> n.matches(".*(frost|snow|winter|glacier|icy|spruce|fir|hearthside).*")),
                "no snowy names in " + snowy);
    }

    @Test
    void everyBundledShapeFillsInEverySetting() {
        HouseNames.Words w = bundled();
        for (Setting s : Setting.values()) {
            List<String> shapes = new java.util.ArrayList<>(w.shapes());
            shapes.addAll(w.setting_shapes().getOrDefault(s, List.of()));
            for (String shape : shapes) {
                HouseNames.Words one = new HouseNames.Words(w.words(), List.of(shape), w.setting_words(), Map.of());
                assertNotNull(HouseNames.suggest(one, s, Set.of(), new Random(5)), s + ": " + shape);
            }
        }
    }

    @Test
    void biomesMapToSettings() {
        assertEquals(Setting.SNOWY, HouseNames.setting_of("minecraft:snowy_taiga"));
        assertEquals(Setting.SNOWY, HouseNames.setting_of("minecraft:frozen_river"));
        assertEquals(Setting.ARID, HouseNames.setting_of("minecraft:desert"));
        assertEquals(Setting.ARID, HouseNames.setting_of("minecraft:eroded_badlands"));
        assertEquals(Setting.WATER, HouseNames.setting_of("minecraft:river"));
        assertEquals(Setting.WATER, HouseNames.setting_of("minecraft:mangrove_swamp"));
        assertEquals(Setting.WOODLAND, HouseNames.setting_of("minecraft:old_growth_birch_forest"));
        assertEquals(Setting.HILLS, HouseNames.setting_of("minecraft:windswept_hills"));
        assertEquals(Setting.MEADOW, HouseNames.setting_of("minecraft:plains"));
        assertEquals(Setting.ANY, HouseNames.setting_of("minecraft:the_void"));
        assertEquals(Setting.ANY, HouseNames.setting_of(null));
    }
}
