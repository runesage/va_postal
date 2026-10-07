package com.vodhanel.minecraft.va_postal.mail;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

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
}
