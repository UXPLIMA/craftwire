package com.uxplima.craftwire.botrunner.javabot.session;

import com.uxplima.craftwire.botrunner.javabot.net.Wire;
import com.uxplima.craftwire.botrunner.net.NetSim;
import io.netty.channel.EventLoopGroup;
import java.security.PublicKey;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import javax.crypto.SecretKey;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundAcceptCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;

/**
 * One Java connection through its protocol phases, as the vanilla client walks them: handshake, login (offline, or
 * online with encryption and the session join), configuration (registries, known packs, code of conduct) and play,
 * and back to configuration when the server asks (proxies switching servers). Connection-level packets (keep-alive,
 * ping, phase changes) are answered here on the event loop at once; everything else goes to the bot's {@link Sink}.
 */
public final class JavaSession implements Wire.Listener {
    /** Who the bot plays as; `accessToken` null for offline mode. */
    public record Identity(String name, UUID uuid, String accessToken) {}

    /** The bot side. `packet` and `closed` run on the event loop; the bot hands them to its own thread. */
    public interface Sink {
        void playing(RegistryAccess registries);

        void packet(Packet<?> packet);

        void closed(String reason);
    }

    private enum Phase { LOGIN, CONFIGURATION, PLAY, CLOSED }

    private final Identity identity;
    private final ClientInformation information;
    private final String brand;
    private final Sink sink;
    private final BooleanSupplier creative;
    private final Executor blocking;
    private Wire wire;
    private Phase phase = Phase.LOGIN;
    private RegistrySync registries = new RegistrySync();

    private JavaSession(Identity identity, ClientInformation information, String brand, Sink sink, BooleanSupplier creative, Executor blocking) {
        this.identity = identity;
        this.information = information;
        this.brand = brand;
        this.sink = sink;
        this.creative = creative;
        this.blocking = blocking;
    }

    /**
     * Connects and starts the login; the bot is told `playing` once the play phase begins. `creative` answers the
     * play protocol's question whether the player has infinite materials (it changes how creative slots encode).
     * `netsim` (or null) simulates a bad network below everything else.
     */
    public static CompletableFuture<JavaSession> open(EventLoopGroup group, String host, int port, int protocol, Identity identity,
            ClientInformation information, String brand, Sink sink, BooleanSupplier creative, Executor blocking, NetSim netsim) {
        JavaSession s = new JavaSession(identity, information, brand, sink, creative, blocking);
        return Wire.connect(group, host, port, LoginProtocols.CLIENTBOUND, HandshakeProtocols.SERVERBOUND, s, netsim).thenApply(w -> {
            s.wire = w;
            w.loop().execute(() -> {
                w.send(new ClientIntentionPacket(protocol, host, port, ClientIntent.LOGIN));
                w.outbound(LoginProtocols.SERVERBOUND);
                w.send(new ServerboundHelloPacket(identity.name(), identity.uuid()));
            });
            return s;
        });
    }

    /** Sends a packet of the play protocol; dropped outside play (during a reconfiguration the server would kick for it). */
    public void send(Packet<?> packet) {
        Wire w = wire;
        if (w == null) return;
        w.loop().execute(() -> {
            if (phase == Phase.PLAY) w.send(packet);
        });
    }

    /** Sends in whatever phase the connection is in (resource pack answers are valid in configuration and play). */
    public void sendCommon(Packet<?> packet) {
        Wire w = wire;
        if (w != null) w.send(packet);
    }

    public void close(String reason) {
        Wire w = wire;
        if (w != null) w.close(reason);
    }

    @Override
    public void packet(Packet<?> packet) {
        switch (packet) {
            case ClientboundKeepAlivePacket k -> wire.send(new ServerboundKeepAlivePacket(k.getId()));
            case ClientboundPingPacket p -> wire.send(new ServerboundPongPacket(p.getId()));
            case ClientboundCookieRequestPacket c -> wire.send(new ServerboundCookieResponsePacket(c.key(), null));
            case ClientboundLoginDisconnectPacket d -> wire.close("kicked: " + d.reason().getString());
            case ClientboundDisconnectPacket d -> wire.close("kicked: " + d.reason().getString());
            default -> {
                switch (phase) {
                    case LOGIN -> login(packet);
                    case CONFIGURATION -> configuration(packet);
                    case PLAY -> play(packet);
                    case CLOSED -> { }
                }
            }
        }
    }

    private void login(Packet<?> packet) {
        switch (packet) {
            case ClientboundHelloPacket h -> encrypt(h);
            case ClientboundLoginCompressionPacket c -> wire.compression(c.getCompressionThreshold());
            // Login plugins (proxy forwarding and the like): answer "not understood", as a vanilla client does.
            case ClientboundCustomQueryPacket q -> wire.send(new ServerboundCustomQueryAnswerPacket(q.transactionId(), null));
            case ClientboundLoginFinishedPacket f -> {
                wire.inbound(ConfigurationProtocols.CLIENTBOUND);
                wire.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
                wire.outbound(ConfigurationProtocols.SERVERBOUND);
                enterConfiguration();
            }
            default -> { }
        }
    }

    private void enterConfiguration() {
        phase = Phase.CONFIGURATION;
        registries = new RegistrySync();
        wire.send(new ServerboundClientInformationPacket(information));
        wire.send(new ServerboundCustomPayloadPacket(new BrandPayload(brand)));
    }

    /** Online mode: join on the session server off the event loop, then answer with the key and switch to AES. */
    private void encrypt(ClientboundHelloPacket h) {
        if (identity.accessToken() == null) {
            wire.close("the server is in online mode; spawn with a signed-in account (account) or use an offline-mode server");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                SecretKey secret = Crypt.generateSecretKey();
                PublicKey key = h.getPublicKey();
                if (h.shouldAuthenticate()) {
                    SessionServer.join(identity.accessToken(), identity.uuid().toString().replace("-", ""),
                            SessionServer.serverHash(h.getServerId(), key, secret));
                }
                ServerboundKeyPacket answer = new ServerboundKeyPacket(secret, key, h.getChallenge());
                wire.loop().execute(() -> {
                    wire.send(answer);
                    try {
                        wire.encryption(secret);
                    } catch (CryptException e) {
                        wire.close("encryption failed: " + e.getMessage());
                    }
                });
            } catch (CryptException | RuntimeException e) {
                wire.close("online login failed: " + e.getMessage());
            }
        }, blocking);
    }

    private void configuration(Packet<?> packet) {
        switch (packet) {
            case ClientboundSelectKnownPacks k -> wire.send(new ServerboundSelectKnownPacks(List.of()));
            case ClientboundRegistryDataPacket r -> registries.data(r);
            case ClientboundUpdateTagsPacket t -> registries.tags(t);
            case ClientboundCodeOfConductPacket c -> wire.send(ServerboundAcceptCodeOfConductPacket.INSTANCE);
            case ClientboundFinishConfigurationPacket f -> enterPlay();
            default -> sink.packet(packet);
        }
    }

    private void enterPlay() {
        RegistryAccess access;
        try {
            access = registries.build();
        } catch (RuntimeException e) {
            wire.close("could not read the server's registries: " + e);
            return;
        }
        wire.inbound(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access)));
        wire.send(ServerboundFinishConfigurationPacket.INSTANCE);
        wire.outbound(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access), creative::getAsBoolean));
        phase = Phase.PLAY;
        sink.playing(access);
    }

    private void play(Packet<?> packet) {
        if (packet instanceof ClientboundStartConfigurationPacket) {
            // A proxy moving the player to another server, or the server reloading data packs.
            wire.inbound(ConfigurationProtocols.CLIENTBOUND);
            wire.send(ServerboundConfigurationAcknowledgedPacket.INSTANCE);
            wire.outbound(ConfigurationProtocols.SERVERBOUND);
            enterConfiguration();
            sink.packet(packet);
            return;
        }
        sink.packet(packet);
    }

    @Override
    public void closed(String reason) {
        phase = Phase.CLOSED;
        sink.closed(reason);
    }
}
