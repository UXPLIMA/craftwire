package com.uxplima.craftwire.paper.compat;

import com.uxplima.craftwire.core.VersionOrder;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;

/**
 * What differs between the supported Minecraft versions on the server (only the bots touch server internals).
 * Each version has its implementation in agent-paper/compat/<mc>, compiled against that version; the plugin jar
 * carries all of them and uses the newest one not newer than the running server.
 */
public interface ServerCompat {
    /** Confirms the pending teleport `id` the way a client does once it has moved. */
    void acceptTeleport(ServerGamePacketListenerImpl listener, ServerPlayer player, int id);

    /** What a client sends after using an item or attacking an entity with `hand` (the arm swing, where one exists). */
    void swingAfterInteraction(ServerGamePacketListenerImpl listener, InteractionHand hand);

    /** The text a client would show for a player chat message. */
    String chatText(ClientboundPlayerChatPacket packet);

    static ServerCompat get() {
        return Holder.INSTANCE;
    }

    /** The bundled version whose code runs, e.g. "26.3". */
    static String selectedVersion() {
        return Holder.SELECTED;
    }

    final class Holder {
        private static final String SELECTED = VersionOrder.select(Bukkit.getMinecraftVersion(), supported());
        private static final ServerCompat INSTANCE = load();

        private Holder() {}

        private static ServerCompat load() {
            String name = "com.uxplima.craftwire.paper.compat.v" + SELECTED.replace('.', '_') + ".ServerCompatImpl";
            try {
                return (ServerCompat) Class.forName(name).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Craftwire: no server code for Minecraft " + SELECTED, e);
            }
        }

        private static List<String> supported() {
            Properties p = new Properties();
            try (InputStream in = ServerCompat.class.getResourceAsStream("/craftwire-compat.properties")) {
                if (in == null) throw new IllegalStateException("craftwire-compat.properties missing from the plugin jar");
                p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return Arrays.stream(p.getProperty("versions").split(",")).map(String::trim).toList();
        }
    }
}
