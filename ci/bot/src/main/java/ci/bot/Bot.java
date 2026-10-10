package ci.bot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetDefaultSpawnPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Headless bot player for CI. Joins an offline-mode server as a named player, reads one instruction per line
 * from stdin, and prints one event per line to stdout (flushed), so a shell script can drive it through a
 * fifo and grep its log.
 *
 * Usage: Bot <host> <port> <name>
 *
 * Stdin:
 *   /cmd args    sends the chat command "cmd args"      (a lone "/" sends the empty command, which Postal
 *                                                        reads as "confirm my pending command")
 *   quit         disconnects and exits
 *
 * Stdout (each line is "<epoch-millis> <EVENT> <detail>"):
 *   JOINED                  the server accepted the login (play state)
 *   COMPASS <dim> <x> <y> <z>   a "set default spawn position" packet, which is what Player#setCompassTarget sends
 *   CHAT <text>             a system or player chat message, flattened to plain text
 *   SENT <text>             an instruction the bot sent
 *   DISCONNECTED <reason>   the connection ended
 *   ERROR <detail>          a packet that could not be handled
 */
public final class Bot {
    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);

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
                } else if (packet instanceof ClientboundSetDefaultSpawnPositionPacket p) {
                    GlobalPos g = p.getGlobalPos();
                    event("COMPASS", g.getDimension().asString() + " " + g.getX() + " " + g.getY() + " " + g.getZ());
                } else if (packet instanceof ClientboundSystemChatPacket p) {
                    event("CHAT", plain(p.getContent()));
                } else if (packet instanceof ClientboundPlayerChatPacket p) {
                    event("CHAT", p.getContent());
                } else if (packet instanceof ClientboundDisguisedChatPacket p) {
                    event("CHAT", plain(p.getMessage()));
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
            if (line.startsWith("/")) {
                event("SENT", line);
                session.send(new ServerboundChatCommandPacket(line.substring(1)));
            } else if (!line.isEmpty()) {
                event("ERROR", "unknown instruction: " + line);
            }
        }
        session.disconnect("bye");
        System.exit(0);
    }
}
