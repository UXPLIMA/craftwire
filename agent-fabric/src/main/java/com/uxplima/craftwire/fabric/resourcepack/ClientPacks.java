package com.uxplima.craftwire.fabric.resourcepack;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;

/**
 * Server resource packs for client_process clients: `-Dcraftwire.resourcePacks=accept|decline|prompt` answers every
 * pack the way the server list's "Server Resource Packs" setting does (prompt shows vanilla's screen), and each answer
 * the client sends becomes a `resource_pack` event, so wait_for {condition:'resource_pack'} sees the pack load or fail.
 */
public final class ClientPacks {
    private static final ServerData.ServerPackStatus POLICY = policy(System.getProperty("craftwire.resourcePacks", "prompt"));
    private static volatile Consumer<JsonObject> sink = e -> { };

    private ClientPacks() {}

    public static void install(Consumer<JsonObject> events) {
        sink = events;
    }

    /** Called as a pack push arrives (netty thread, then again on the render thread); vanilla reads the status next. */
    public static void applyPolicy(ServerData server) {
        if (server != null && POLICY != ServerData.ServerPackStatus.PROMPT) server.setResourcePackStatus(POLICY);
    }

    /** Every ServerboundResourcePackPacket the client sends: accepted, downloaded, then loaded or a failure. */
    public static void reported(ServerboundResourcePackPacket p) {
        JsonObject d = new JsonObject();
        d.addProperty("id", p.id().toString());
        d.addProperty("status", p.action().name().toLowerCase(Locale.ROOT));
        d.addProperty("final", p.action().isTerminal());
        sink.accept(d);
    }

    static ServerData.ServerPackStatus policy(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "accept" -> ServerData.ServerPackStatus.ENABLED;
            case "decline" -> ServerData.ServerPackStatus.DISABLED;
            default -> ServerData.ServerPackStatus.PROMPT;
        };
    }
}
