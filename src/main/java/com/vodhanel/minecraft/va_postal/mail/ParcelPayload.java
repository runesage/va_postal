package com.vodhanel.minecraft.va_postal.mail;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A parcel's record payload, {@code PARCEL_V1} (docs/design/persistent-state.md §5): its shipping label (title,
 * author, pages as text, so a lost label can be rebuilt), the chest it was packed in, and its items, each the
 * bytes of Paper's {@code ItemStack.serializeAsBytes()} with the chest slot it came from. The full item is kept,
 * so enchantments, names, lore and any other data survive shipping.
 * <p>
 * Pure data and JSON, with no Bukkit types, so it's unit-testable; {@link Parcels} converts the item bytes.
 */
public final class ParcelPayload {
    public static final String FORMAT = "PARCEL_V1";
    private static final Gson GSON = new Gson();

    public final String title;
    public final String author;
    public final List<String> pages;
    /** The chest the parcel was packed in: {@code world,x,y,z,FACING}. */
    public final String chest;
    public final List<Integer> slots;
    public final List<byte[]> items;

    public ParcelPayload(String title, String author, List<String> pages, String chest, List<Integer> slots,
                         List<byte[]> items) {
        if (slots.size() != items.size()) {
            throw new IllegalArgumentException("one slot per item");
        }
        this.title = title;
        this.author = author;
        this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
        this.chest = chest;
        this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    public byte[] encode() {
        JsonObject o = new JsonObject();
        o.addProperty("title", title);
        o.addProperty("author", author);
        JsonArray p = new JsonArray();
        pages.forEach(p::add);
        o.add("pages", p);
        o.addProperty("chest", chest);
        JsonArray list = new JsonArray();
        Base64.Encoder b64 = Base64.getEncoder();
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = new JsonObject();
            item.addProperty("slot", slots.get(i));
            item.addProperty("data", b64.encodeToString(items.get(i)));
            list.add(item);
        }
        o.add("items", list);
        return GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    public static ParcelPayload decode(byte[] bytes) {
        JsonObject o = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
        List<String> pages = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("pages")) {
            pages.add(e.getAsString());
        }
        List<Integer> slots = new ArrayList<>();
        List<byte[]> items = new ArrayList<>();
        Base64.Decoder b64 = Base64.getDecoder();
        for (JsonElement e : o.getAsJsonArray("items")) {
            JsonObject item = e.getAsJsonObject();
            slots.add(item.get("slot").getAsInt());
            items.add(b64.decode(item.get("data").getAsString()));
        }
        return new ParcelPayload(o.get("title").getAsString(), o.get("author").getAsString(), pages,
                o.has("chest") && !o.get("chest").isJsonNull() ? o.get("chest").getAsString() : null, slots, items);
    }

    // ---- Item ids inside the stored bytes (best effort) ---------------------------------------
    // An item's bytes are (gzipped) NBT. If a Minecraft upgrade removed the item, Paper can't rebuild it, but its
    // old id is still readable here, so the player can be told what couldn't be delivered.

    private static final byte[] ID_TAG = {8, 0, 2, 'i', 'd'};

    /** The item id ({@code minecraft:...}) stored in an item's bytes, or null if it can't be read. */
    public static String item_id(byte[] item) {
        try {
            byte[] nbt = unzip(item);
            int at = find(nbt, ID_TAG);
            if (at < 0 || at + ID_TAG.length + 2 > nbt.length) {
                return null;
            }
            int start = at + ID_TAG.length + 2;
            int len = ((nbt[start - 2] & 0xFF) << 8) | (nbt[start - 1] & 0xFF);
            return start + len <= nbt.length ? new String(nbt, start, len, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The item's bytes with its id replaced by {@code id} (testing: {@code /postal testparcel ... retired} uses it
     * to ship an item this version of Minecraft doesn't have). Returns the bytes unchanged if no id is found.
     */
    public static byte[] with_item_id(byte[] item, String id) {
        try {
            boolean zipped = zipped(item);
            byte[] nbt = unzip(item);
            int at = find(nbt, ID_TAG);
            if (at < 0) {
                return item;
            }
            int start = at + ID_TAG.length + 2;
            int len = ((nbt[start - 2] & 0xFF) << 8) | (nbt[start - 1] & 0xFF);
            byte[] name = id.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(nbt, 0, start - 2);
            out.write(name.length >> 8);
            out.write(name.length & 0xFF);
            out.write(name);
            out.write(nbt, start + len, nbt.length - start - len);
            if (!zipped) {
                return out.toByteArray();
            }
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(z)) {
                gz.write(out.toByteArray());
            }
            return z.toByteArray();
        } catch (IOException | RuntimeException e) {
            return item;
        }
    }

    private static boolean zipped(byte[] b) {
        return b.length > 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B;
    }

    private static byte[] unzip(byte[] b) throws IOException {
        if (!zipped(b)) {
            return b;
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
            return in.readAllBytes();
        }
    }

    private static int find(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
