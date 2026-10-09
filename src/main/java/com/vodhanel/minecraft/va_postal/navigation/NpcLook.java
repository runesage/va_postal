package com.vodhanel.minecraft.va_postal.navigation;

import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.Config;
import com.vodhanel.minecraft.va_postal.VA_postal;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

/**
 * How PostMen and the PostMaster look: skin, optional uniform, and the item in their hand.
 * <p>
 * Skins: {@code settings.skin.local|central} is {@code bundled} (Postal's own skins, see dev/skins),
 * {@code none} (default Steve/Alex), {@code custom} (the texture/signature under
 * {@code settings.skin.custom.local|central}, e.g. from mineskin.org), or a player name to copy. Without
 * this Citizens looks up the Mojang account named like the NPC ("PostMan"), i.e. a random player's skin.
 * <p>
 * Uniform: off unless {@code settings.uniform.enabled}; each piece is {@code MATERIAL} or
 * {@code MATERIAL #RRGGBB} (the colour dyes leather), or {@code none}.
 */
public final class NpcLook {
    private static final String POSTMAN_TEXTURE =
            "ewogICJ0aW1lc3RhbXAiIDogMTc5MDk2MDY2NDM1MiwKICAicHJvZmlsZUlkIiA6ICI2YjllM2Q0MDRiZmY0MTEyODJiY2Vk" +
            "OTAzY2M3ZDJmZiIsCiAgInByb2ZpbGVOYW1lIiA6ICJsdWgyOSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAg" +
            "InRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5u" +
            "ZXQvdGV4dHVyZS85MzY1NTYzZGZkN2EwMDlkNjQ0YjcwZWJkZGZmMjA1ZWExZWMwZmQ3ODk5MGVhODM5MmM2Y2ZmOTkwMjg1" +
            "ZmZiIgogICAgfQogIH0KfQ==";
    private static final String POSTMAN_SIGNATURE =
            "fj9DJy/aPd3uNaw7idDbWefMd0R9ARQg1xjSf2BMyL7GLJVS8kWtYEypjgGLBZCQoWPI9gVvihSZYO15mCVcrvcwZ4LIUK14" +
            "o7CfetDyV+aK5UbksBu58ToNhNcBZTAYelaNE4z9ojaO11D+W9PxatpmXcsIMFukNemQAXqsfPWh0adnLlxtYPoSsDksrPvW" +
            "R0mGZCLWOkvjO/TBNtZsNp2XGDwMkHtBtO5s9XL7iz3p0y1BM/RUJLHOWT6WDLba1Xcu2Cx4S06QZ3VcBkVKayDElKHCqEuh" +
            "t2uralH6dapp3B7/rTjpEYzMyELRJ+p2hxwMxeqlT96BjR82+51t8iqKjDzRDVSc/cIeSM2HTZK7sDl1h14nDnhwokOjl0oD" +
            "bzml82+X+tkc/44hNHTT6jyQ34Ew1P0thURSC1nB7q+XwvGVR5ZBCgIiOT6lLPhm1w7D2ix/QQ86hA4//8eb5h7bcQFDJs2Z" +
            "B2EEQjfmoiexLMFLSA+76RqQ//9rUbkuGnABPGpNElOoVk0NPiMQD0NHrXzTeMm1mQYpB98fc2l45a+JqbH3+sxgKZXYHItI" +
            "E8IcqVFxDaXaDWh99Q9sViMADHl/opHTYu/zQ5yG7SUNKwd2TeMUBpZv//EKPymVlmafSYp+FdTX4VJIq8no/Q3TIiPDZ+xk" +
            "VGcYSK/FguU=";
    private static final String POSTMASTER_TEXTURE =
            "ewogICJ0aW1lc3RhbXAiIDogMTc5MDk2MDY3NTUyNCwKICAicHJvZmlsZUlkIiA6ICI2MTU1NTMyOTY2OWM0ZDA5YmFiOGJl" +
            "NDNkYWUwYTRjMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJOb3Ryb19DbGllbnQiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0" +
            "cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5l" +
            "Y3JhZnQubmV0L3RleHR1cmUvMmFiODY1ODIyNDM0NGQ4OGY3MzgxMTc2ZjY3NjViMDU4ZDRlZWRlOWRkZmMzMTFmNDM1YWFh" +
            "NzJkOTllMmYxZSIKICAgIH0KICB9Cn0=";
    private static final String POSTMASTER_SIGNATURE =
            "nTZ0NuD8ip9CkRsrDbuoNxglXwakU+XmRiqFdhAxjfVLNx78JaDevcQaBeT8kdfYTPSfC9u3zaao2kePwniTOZiNY7xDkPYF" +
            "XuTY/hwTUM1DM34158WR4LB5q9wjjvDGsFWLpGpud3KYjBmhOgZTOBWkFGJrFt/e4Po6QWUJ4TrkXE57fEJ9ArrpuHG6lqCy" +
            "lMUp0p74O6jWW2MMBgYf4zAgYDw1F+ThRjHpXO3lwlbFAiqS9sHmIYvpGSDH+/T2dBbd87/OGlJyUfX3ovMV0v+4upIdA9DX" +
            "GyzT5E/Hgb19qRqYI+qLmJSS31i0W2cHhgFdvtbSmfQ25ycucQfX03Sz5g+gDJ3PVNNPHf8u+tEE0VFpuJpXVAwqseMvrdPO" +
            "BY7eFVWxNkPTO47i1prlwws7h0Mro60mdn9tWq58Zk9vmneJCCFbRZf2cj9jGCan1wJgo8x9U85ihst/JXJrCQhI7SpjN2Bp" +
            "6NrgEGwakcgmO+ewgHNP5o2xbHo3j+DgTPhrvZ638QeZO4ecx1ftMrGmp5IaaBafduiDW5tZsaZsy0xMszLjGX0au8b12k+n" +
            "VHRp5E9v/aoEj34iTvLR2D7ML8sFkmd3BLH5QbxvHOrlVAqE15yuvQUwRY4+AIMhV/vooo8VXV3dhuksH7mpLApKIoHnfJfZ" +
            "qBRFkAB3yyg=";

    private static final String[] UNIFORM_PIECES = {"helmet", "chestplate", "leggings", "boots"};
    private static final Equipment.EquipmentSlot[] UNIFORM_SLOTS = {
            Equipment.EquipmentSlot.HELMET, Equipment.EquipmentSlot.CHESTPLATE,
            Equipment.EquipmentSlot.LEGGINGS, Equipment.EquipmentSlot.BOOTS};

    private NpcLook() {
    }

    /** Applies the configured skin. Call before spawning so the NPC never shows the name-lookup skin. */
    public static void skin(NPC npc, boolean local) {
        skin(npc, local ? "local" : "central");
    }

    /** {@code role}: {@code local} (postman), {@code central} (postmaster) or {@code purser} (the network's courier). */
    public static void skin(NPC npc, String role) {
        if (npc == null) {
            return;
        }
        boolean local = "local".equals(role);
        String setting = config_string("settings.skin." + role, "bundled").trim();
        SkinTrait trait = npc.getOrAddTrait(SkinTrait.class);
        trait.setFetchDefaultSkin(false);
        if ("none".equalsIgnoreCase(setting)) {
            trait.clearTexture();
        } else if ("custom".equalsIgnoreCase(setting)) {
            String texture = config_string("settings.skin.custom." + role + ".texture", "").trim();
            String signature = config_string("settings.skin.custom." + role + ".signature", "").trim();
            if (texture.isEmpty() || signature.isEmpty()) {
                Util.cinform("\033[0;33m[Postal] settings.skin.custom." + role + " needs a texture and signature; using the bundled skin");
                bundled(trait, local);
            } else {
                trait.setSkinPersistent("va_postal_custom_" + role, signature, texture);
            }
        } else if ("bundled".equalsIgnoreCase(setting) || setting.isEmpty()) {
            bundled(trait, local);
        } else {
            trait.setSkinName(setting, true);
        }
    }

    private static void bundled(SkinTrait trait, boolean local) {
        if (local) {
            trait.setSkinPersistent("va_postal_postman", POSTMAN_SIGNATURE, POSTMAN_TEXTURE);
        } else {
            trait.setSkinPersistent("va_postal_postmaster", POSTMASTER_SIGNATURE, POSTMASTER_TEXTURE);
        }
    }

    /** Puts on (or, with the uniform disabled, takes off) the configured uniform. */
    public static void uniform(NPC npc, boolean local) {
        uniform(npc, local ? "local" : "central",
                "true".equalsIgnoreCase(config_string("settings.uniform.enabled", "false").trim()));
    }

    /** The uniform for {@code role} ({@code settings.uniform.<role>.*}), or none when not {@code enabled}. */
    public static void uniform(NPC npc, String role, boolean enabled) {
        if (npc == null) {
            return;
        }
        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        for (int i = 0; i < UNIFORM_PIECES.length; i++) {
            ItemStack piece = null;
            if (enabled) {
                String path = "settings.uniform." + role + "." + UNIFORM_PIECES[i];
                piece = uniform_piece(config_string(path, "none"));
            }
            equipment.set(UNIFORM_SLOTS[i], piece);
        }
    }

    /** {@code MATERIAL} or {@code MATERIAL #RRGGBB}; null for "none" or anything unrecognised. */
    static ItemStack uniform_piece(String spec) {
        if (spec == null) {
            return null;
        }
        String[] parts = spec.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty() || "none".equalsIgnoreCase(parts[0])) {
            return null;
        }
        Material material = Material.matchMaterial(parts[0]);
        if (material == null || !material.isItem()) {
            Util.cinform("\033[0;33m[Postal] Unknown uniform item: " + spec);
            return null;
        }
        ItemStack item = new ItemStack(material, 1);
        Color color = parts.length > 1 ? parse_color(parts[1]) : null;
        if (color != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof LeatherArmorMeta) {
                ((LeatherArmorMeta) meta).setColor(color);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    static Color parse_color(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return h.length() == 6 ? Color.fromRGB(Integer.parseInt(h, 16)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Shows what the NPC is doing: e.g. a book while carrying mail, a chest for parcels, or nothing. */
    public static void hold(NPC npc, ItemStack item) {
        if (npc == null) {
            return;
        }
        npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, item == null ? null : item.clone());
    }

    public static void hold(NPC npc, Material material) {
        hold(npc, material == null ? null : new ItemStack(material, 1));
    }

    private static String config_string(String path, String fallback) {
        try {
            String value = VA_postal.plugin.getConfig().getString(Config.path_format(path));
            return value != null ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
