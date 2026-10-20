package ci.bot;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.GlobalPos;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundMapItemDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetDefaultSpawnPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Headless bot player for CI. Joins an offline-mode server as a named player, reads one instruction per line
 * from stdin, and prints one event per line to stdout (flushed), so a shell script can drive it through a
 * fifo and grep its log.
 *
 * Usage: Bot <host> <port> <name>
 *
 * Stdin:
 *   /cmd args             sends the chat command "cmd args"  (a lone "/" sends the empty command, which Postal
 *                                                             reads as "confirm my pending command")
 *   interact <x> <y> <z>  right-clicks the entity nearest that point (within 3 blocks), e.g. an NPC
 *   click <slot> [right]  clicks a slot of the open container (left, or right)
 *   close                 closes the open container
 *   input <key> <text>    sets a text field of the open dialog (otherwise its initial value is sent)
 *   press <label>         presses the dialog button with that label (its custom action, with the fields)
 *   use <x> <y> <z> [face] uses the held item on that block (face: up by default)
 *   dig <x> <y> <z>       starts breaking that block (in creative that breaks it at once)
 *   quit                  disconnects and exits
 *
 * Stdout (each line is "<epoch-millis> <EVENT> <detail>"):
 *   JOINED                  the server accepted the login (play state)
 *   COMPASS <dim> <x> <y> <z>   a "set default spawn position" packet, which is what Player#setCompassTarget sends
 *   CHAT <text>             a system or player chat message, flattened to plain text
 *   ENTITY <id> <type> <x> <y> <z>  an entity came into view
 *   SCREEN <id> <title>     a container opened
 *   ITEM <container>/<slot> <name>  a named item in a container (0: the player's inventory); a written book is
 *                           "Book <title> by <author>"
 *   CLOSED <id>             the server closed a container
 *   DIALOG <title> | <field>=<initial>, ... | <button>, ...   a dialog opened
 *   SENT <text>             an instruction the bot sent
 *   DISCONNECTED <reason>   the connection ended
 *   ERROR <detail>          a packet or instruction that could not be handled
 */
public final class Bot {
    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);

    private record Seen(String type, double x, double y, double z) {
    }

    /** A dialog button with a custom action: its label, action id and fixed payload. */
    record Button(String label, String id, NbtMap additions) {
    }

    private static final Map<Integer, Seen> entities = new ConcurrentHashMap<>();
    private static volatile int container = -1;
    /** What each map last showed, so a map is logged only when it changes. */
    private static final Map<Integer, String> maps = new ConcurrentHashMap<>();
    private static volatile int state_id = 0;
    private static volatile List<Button> buttons = List.of();
    private static final Map<String, String> fields = new LinkedHashMap<>();
    private static int sequence = 0;

    private static synchronized void event(String kind, String detail) {
        OUT.println(System.currentTimeMillis() + " " + kind + (detail.isEmpty() ? "" : " " + detail));
    }

    /** Plain text of a component: literal text and children; translatable ones show their key and arguments. */
    static String plain(Component c) {
        StringBuilder sb = new StringBuilder();
        append(sb, c);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Component c) {
        if (c instanceof TextComponent t) {
            sb.append(t.content());
        } else if (c instanceof TranslatableComponent t) {
            sb.append(t.key());
            for (var arg : t.arguments()) {
                sb.append(' ');
                append(sb, arg.asComponent());
            }
        }
        for (Component child : c.children()) {
            append(sb, child);
        }
    }

    /** Plain text of a text component in NBT form (a string, a compound with text/extra, or a list of them). */
    static String plain_nbt(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof String s) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        if (o instanceof Map<?, ?> m) {
            Object text = m.get("text");
            if (text == null) {
                text = m.get("");
            }
            if (text != null) {
                sb.append(plain_nbt(text));
            } else if (m.get("translate") != null) {
                sb.append(m.get("translate"));
            }
            Object extra = m.get("extra");
            if (extra instanceof List<?> l) {
                for (Object e : l) {
                    sb.append(plain_nbt(e));
                }
            }
        } else if (o instanceof List<?> l) {
            for (Object e : l) {
                sb.append(plain_nbt(e));
            }
        }
        return sb.toString();
    }

    /** The custom-action buttons of a dialog in NBT form, and fills {@code initial} with its text fields. */
    static List<Button> dialog_buttons(NbtMap dialog, Map<String, String> initial) {
        List<Button> out = new ArrayList<>();
        if (dialog.get("inputs") instanceof List<?> inputs) {
            for (Object i : inputs) {
                if (i instanceof Map<?, ?> in && in.get("key") instanceof String key) {
                    Object init = in.get("initial");
                    initial.put(key, init == null ? "" : String.valueOf(init));
                }
            }
        }
        List<Object> actions = new ArrayList<>();
        if (dialog.get("actions") instanceof List<?> l) {
            actions.addAll(l);
        }
        for (String single : new String[]{"action", "yes", "no", "exit_action"}) {
            if (dialog.get(single) != null) {
                actions.add(dialog.get(single));
            }
        }
        for (Object a : actions) {
            if (a instanceof Map<?, ?> button) {
                String label = plain_nbt(button.get("label"));
                Object action = button.get("action");
                if (action instanceof Map<?, ?> act && act.get("id") instanceof String id) {
                    Object additions = act.get("additions");
                    out.add(new Button(label, id, additions instanceof NbtMap n ? n : NbtMap.EMPTY));
                } else {
                    out.add(new Button(label, null, NbtMap.EMPTY));
                }
            }
        }
        return out;
    }

    private static String item_name(ItemStack item) {
        if (item == null || item.getDataComponentsPatch() == null) {
            return null;
        }
        Component name = item.getDataComponentsPatch().get(DataComponentTypes.CUSTOM_NAME);
        if (name == null) {
            name = item.getDataComponentsPatch().get(DataComponentTypes.ITEM_NAME);
        }
        if (name == null) {
            // A written book (a letter) is named by its title.
            org.geysermc.mcprotocollib.protocol.data.game.item.component.WrittenBookContent book =
                    item.getDataComponentsPatch().get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
            return book == null || book.getTitle() == null ? null : "Book " + book.getTitle().getRaw() + " by " + book.getAuthor();
        }
        return plain(name);
    }

    private static void items(int id, ItemStack[] items) {
        for (int slot = 0; slot < items.length; slot++) {
            String name = item_name(items[slot]);
            if (name != null) {
                event("ITEM", id + "/" + slot + " " + name);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: Bot <host> <port> <name>");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String name = args[2];

        ClientNetworkSession session = ClientNetworkSessionFactory.factory()
                .setAddress(host, port)
                .setProtocol(new MinecraftProtocol(name))
                .create();

        session.addListener(new SessionAdapter() {
            @Override
            public void connected(ConnectedEvent event) {
                event("CONNECTED", "");
            }

            @Override
            public void packetReceived(Session s, Packet packet) {
                if (packet instanceof ClientboundLoginPacket) {
                    event("JOINED", "");
                } else if (packet instanceof ClientboundPlayerPositionPacket p) {
                    // Confirm server teleports (/tp), and say the world is loaded: until then the server ignores
                    // a player's clicks.
                    s.send(new ServerboundAcceptTeleportationPacket(p.getId()));
                    s.send(ServerboundPlayerLoadedPacket.INSTANCE);
                } else if (packet instanceof ClientboundSetDefaultSpawnPositionPacket p) {
                    GlobalPos g = p.getGlobalPos();
                    event("COMPASS", g.getDimension().asString() + " " + g.getX() + " " + g.getY() + " " + g.getZ());
                } else if (packet instanceof ClientboundSystemChatPacket p) {
                    if (!p.isOverlay()) {
                        event("CHAT", plain(p.getContent()));
                    }
                } else if (packet instanceof ClientboundPlayerChatPacket p) {
                    event("CHAT", p.getContent());
                } else if (packet instanceof ClientboundDisguisedChatPacket p) {
                    event("CHAT", plain(p.getMessage()));
                } else if (packet instanceof ClientboundAddEntityPacket p) {
                    String type = String.valueOf(p.getType()).toLowerCase(Locale.ROOT);
                    entities.put(p.getEntityId(), new Seen(type, p.getX(), p.getY(), p.getZ()));
                    event("ENTITY", p.getEntityId() + " " + type + " " + fmt(p.getX()) + " " + fmt(p.getY()) + " " + fmt(p.getZ()));
                } else if (packet instanceof ClientboundRemoveEntitiesPacket p) {
                    for (int id : p.getEntityIds()) {
                        entities.remove(id);
                    }
                } else if (packet instanceof ClientboundOpenScreenPacket p) {
                    container = p.getContainerId();
                    event("SCREEN", p.getContainerId() + " " + plain(p.getTitle()));
                } else if (packet instanceof ClientboundContainerSetContentPacket p) {
                    if (p.getContainerId() == container) {
                        state_id = p.getStateId();
                    }
                    items(p.getContainerId(), p.getItems());
                } else if (packet instanceof ClientboundContainerSetSlotPacket p) {
                    if (p.getContainerId() == container) {
                        state_id = p.getStateId();
                    }
                    String n = item_name(p.getItem());
                    if (n != null) {
                        event("ITEM", p.getContainerId() + "/" + p.getSlot() + " " + n);
                    }
                } else if (packet instanceof ClientboundMapItemDataPacket p) {
                    // A map's pixels and labels: logged when they change, e.g. "MAP 3 pixels=16384 labels=Central,Testville".
                    List<String> labels = new ArrayList<>();
                    if (p.getIcons() != null) {
                        for (var icon : p.getIcons()) {
                            if (icon.getDisplayName() != null) {
                                labels.add(plain(icon.getDisplayName()));
                            }
                        }
                    }
                    int pixels = p.getData() == null ? 0 : p.getData().getData().length;
                    String seen = "pixels=" + pixels + " labels=" + String.join(",", labels);
                    if (pixels > 0 && !seen.equals(maps.put(p.getMapId(), seen))) {
                        event("MAP", p.getMapId() + " " + seen);
                    }
                } else if (packet instanceof ClientboundContainerClosePacket p) {
                    event("CLOSED", String.valueOf(p.getContainerId()));
                    container = -1;
                } else if (packet instanceof ClientboundShowDialogGamePacket p) {
                    if (!p.getDialog().isCustom()) {
                        event("DIALOG", "(registered dialog " + p.getDialog().id() + ")");
                        return;
                    }
                    NbtMap dialog = p.getDialog().custom();
                    Map<String, String> initial = new LinkedHashMap<>();
                    List<Button> found = dialog_buttons(dialog, initial);
                    synchronized (fields) {
                        fields.clear();
                        fields.putAll(initial);
                    }
                    buttons = found;
                    List<String> labels = new ArrayList<>();
                    for (Button b : found) {
                        labels.add(b.label());
                    }
                    List<String> shown = new ArrayList<>();
                    initial.forEach((k, v) -> shown.add(k + "=" + v));
                    event("DIALOG", plain_nbt(dialog.get("title")) + " | " + String.join(", ", shown) + " | " + String.join(", ", labels));
                }
            }

            @Override
            public void packetError(PacketErrorEvent e) {
                event("ERROR", String.valueOf(e.getCause()));
                e.setSuppress(true);
            }

            @Override
            public void disconnected(DisconnectedEvent e) {
                event("DISCONNECTED", plain(e.getReason()));
                if (e.getCause() != null) {
                    event("ERROR", e.getCause().toString());
                }
            }
        });
        session.connect();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            line = line.stripTrailing();
            if (line.equals("quit")) {
                break;
            }
            try {
                if (!line.isEmpty()) {
                    instruct(session, line);
                }
            } catch (RuntimeException e) {
                event("ERROR", line + ": " + e);
            }
        }
        session.disconnect("bye");
        System.exit(0);
    }

    private static void instruct(Session session, String line) {
        if (line.startsWith("/")) {
            event("SENT", line);
            session.send(new ServerboundChatCommandPacket(line.substring(1)));
            return;
        }
        String[] w = line.split("\\s+");
        switch (w[0]) {
            case "interact" -> {
                double x = Double.parseDouble(w[1]), y = Double.parseDouble(w[2]), z = Double.parseDouble(w[3]);
                Integer best = null;
                double best_d = 9.0D;
                for (Map.Entry<Integer, Seen> e : entities.entrySet()) {
                    Seen s = e.getValue();
                    double d = (s.x() - x) * (s.x() - x) + (s.y() - y) * (s.y() - y) + (s.z() - z) * (s.z() - z);
                    if (d < best_d) {
                        best = e.getKey();
                        best_d = d;
                    }
                }
                if (best == null) {
                    event("ERROR", "no entity near " + x + " " + y + " " + z);
                    return;
                }
                event("SENT", "interact " + best + " (" + entities.get(best).type() + ")");
                session.send(new ServerboundInteractPacket(best, Hand.MAIN_HAND, Vector3d.from(0.0D, 1.0D, 0.0D), false));
            }
            case "click" -> {
                int slot = Integer.parseInt(w[1]);
                boolean right = w.length > 2 && w[2].equals("right");
                if (container < 0) {
                    event("ERROR", "no container open");
                    return;
                }
                event("SENT", "click " + container + "/" + slot + (right ? " right" : ""));
                session.send(new ServerboundContainerClickPacket(container, state_id, slot, ContainerActionType.CLICK_ITEM,
                        right ? ClickItemAction.RIGHT_CLICK : ClickItemAction.LEFT_CLICK, null, new HashMap<>()));
            }
            case "close" -> {
                event("SENT", "close " + container);
                session.send(new ServerboundContainerClosePacket(Math.max(0, container)));
                container = -1;
            }
            case "input" -> {
                String value = line.substring(line.indexOf(w[1]) + w[1].length()).trim();
                synchronized (fields) {
                    fields.put(w[1], value);
                }
                event("SENT", "input " + w[1] + "=" + value);
            }
            case "press" -> {
                String label = line.substring("press".length()).trim();
                Button button = buttons.stream().filter(b -> b.label().trim().equalsIgnoreCase(label)).findFirst().orElse(null);
                if (button == null || button.id() == null) {
                    event("ERROR", "no dialog button '" + label + "' with an action");
                    return;
                }
                // As the client does for a dynamic/custom action: its fixed payload plus every field's value.
                NbtMapBuilder payload = button.additions().toBuilder();
                synchronized (fields) {
                    fields.forEach(payload::putString);
                }
                event("SENT", "press " + label + " -> " + button.id());
                session.send(new ServerboundCustomClickActionPacket(Key.key(button.id()), payload.build()));
                buttons = List.of();
            }
            case "use" -> {
                Vector3i at = Vector3i.from(Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]));
                Direction face = w.length > 4 ? Direction.valueOf(w[4].toUpperCase(Locale.ROOT)) : Direction.UP;
                event("SENT", "use " + at.getX() + " " + at.getY() + " " + at.getZ() + " " + face);
                session.send(new ServerboundUseItemOnPacket(at, face, Hand.MAIN_HAND, 0.5F, 0.5F, 0.5F, false, false, ++sequence));
            }
            case "dig" -> {
                Vector3i at = Vector3i.from(Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]));
                event("SENT", "dig " + at.getX() + " " + at.getY() + " " + at.getZ());
                session.send(new ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, at, Direction.UP, ++sequence));
            }
            default -> event("ERROR", "unknown instruction: " + line);
        }
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
