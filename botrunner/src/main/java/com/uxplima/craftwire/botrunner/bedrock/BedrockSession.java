package com.uxplima.craftwire.botrunner.bedrock;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bedrock.codec.BatchCodec;
import com.uxplima.craftwire.bedrock.codec.BedrockCodecException;
import com.uxplima.craftwire.bedrock.crypto.Jwt;
import com.uxplima.craftwire.bedrock.packet.Clientbound;
import com.uxplima.craftwire.bedrock.packet.LoginOut;
import com.uxplima.craftwire.bedrock.packet.LoginPackets;
import com.uxplima.craftwire.bedrock.packet.PacketDecoder;
import com.uxplima.craftwire.bedrock.packet.UiPackets;
import com.uxplima.craftwire.bedrock.raknet.RakClient;
import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoopGroup;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One Bedrock connection: RakNet below, batches with compression and encryption on top, and the login handshake up to
 * the point where the server starts sending game packets. NetworkSettings, the encryption handshake and latency
 * probes change or answer the connection at once, so they are handled here on the event loop; everything else
 * goes to the {@link Sink}. Event loop thread; {@link #send} and {@link #close} may be called from any thread.
 */
final class BedrockSession implements RakClient.Listener {
    /** Receives decoded packets and the end of the connection, on the event loop (implementations hand off to the runner loop). */
    interface Sink {
        void packet(Clientbound p);

        void closed(String reason);
    }

    private static final Logger LOG = LogManager.getLogger("craftwire-botrunner");

    private final BatchCodec codec = new BatchCodec();
    private final Sink sink;
    private final byte[] loginRequest;
    private final KeyPair key;
    private final Set<Integer> badPackets = new HashSet<>();
    private volatile RakClient rak;

    private BedrockSession(Sink sink, byte[] loginRequest, KeyPair key) {
        this.sink = sink;
        this.loginRequest = loginRequest;
        this.key = key;
    }

    /** Connects and starts the login; completes once RakNet is up (the game login then runs on its own). */
    static CompletableFuture<BedrockSession> connect(EventLoopGroup group, InetSocketAddress server, Sink sink, byte[] loginRequest, KeyPair key) {
        BedrockSession s = new BedrockSession(sink, loginRequest, key);
        return RakClient.connect(group, server, s).thenApply(r -> {
            s.rak = r;
            s.send(List.of(LoginOut.requestNetworkSettings()));
            return s;
        });
    }

    /** Sends packets in one batch. Encoding runs on the event loop, where the cipher's counter keeps their order. */
    void send(List<ByteBuf> packets) {
        RakClient r = rak;
        if (r == null) {
            packets.forEach(ByteBuf::release);
            return;
        }
        r.loop().execute(() -> {
            try {
                r.send(codec.encode(packets));
            } finally {
                packets.forEach(ByteBuf::release);
            }
        });
    }

    void send(ByteBuf packet) {
        send(List.of(packet));
    }

    void close(String reason) {
        RakClient r = rak;
        if (r != null) r.close(reason);
    }

    double rttMs() {
        RakClient r = rak;
        return r == null ? 0 : r.rttMs();
    }

    @Override
    public void batch(ByteBuf batch) {
        List<ByteBuf> packets;
        try {
            packets = codec.decode(batch);
        } catch (BedrockCodecException | SecurityException e) {
            close("unreadable batch from the server: " + e.getMessage());
            return;
        }
        for (ByteBuf p : packets) {
            int id = PacketDecoder.peekId(p);
            Clientbound c;
            try {
                c = PacketDecoder.decode(p);
            } catch (BedrockCodecException e) {
                // One packet this codec reads wrong should not end the session; the rest of the batch is intact.
                if (badPackets.add(id)) LOG.warn("Bedrock packet {} did not decode: {}", id, e.getMessage());
                continue;
            }
            if (c != null && !handled(c)) sink.packet(c);
        }
    }

    /** Packets that change the connection itself; true when consumed here. */
    private boolean handled(Clientbound c) {
        switch (c) {
            case LoginPackets.NetworkSettings s -> {
                codec.compression(BatchCodec.Compression.of(s.algorithm()), s.threshold());
                send(List.of(LoginOut.login(loginRequest)));
                return true;
            }
            case LoginPackets.ServerHandshake h -> {
                startEncryption(h.jwt());
                return true;
            }
            case UiPackets.Latency l -> {
                if (l.needsResponse()) send(List.of(LoginOut.latency(l.timestamp())));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** ServerToClientHandshake: the session key is SHA-256(salt || ECDH(our key, the server's key)). */
    private void startEncryption(String jwt) {
        try {
            Jwt.Parsed token = Jwt.verifySelf(jwt);
            JsonObject claims = token.claims();
            byte[] salt = Base64.getDecoder().decode(claims.get("salt").getAsString());
            codec.encryption(Jwt.sessionKey(key.getPrivate(), token.signedBy(), salt));
            send(List.of(LoginOut.clientHandshake()));
        } catch (RuntimeException e) {
            close("the server's encryption handshake is invalid: " + e.getMessage());
        }
    }

    @Override
    public void closed(String reason) {
        sink.closed(reason);
    }
}
