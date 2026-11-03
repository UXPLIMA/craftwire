package com.uxplima.craftwire.botrunner.javabot.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Packets to and from frames with the vanilla protocol codecs, for whichever protocol (login, configuration, play)
 * the connection is in. The protocol is switched on the event loop between two frames, the way vanilla's terminal
 * packets do, so the frame after LoginFinished already decodes as configuration. A frame that does not decode
 * (a packet the bot cannot read) is skipped by its length instead of dropping the connection. Netty event loop.
 */
final class PacketCodec {
    private static final Logger LOG = LogManager.getLogger("craftwire-botrunner");

    private volatile ProtocolInfo<?> inbound;
    private volatile ProtocolInfo<?> outbound;

    PacketCodec(ProtocolInfo<?> inbound, ProtocolInfo<?> outbound) {
        this.inbound = inbound;
        this.outbound = outbound;
    }

    void inbound(ProtocolInfo<?> info) {
        inbound = info;
    }

    void outbound(ProtocolInfo<?> info) {
        outbound = info;
    }

    ProtocolInfo<?> inbound() {
        return inbound;
    }

    final class Decoder extends MessageToMessageDecoder<ByteBuf> {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
            ProtocolInfo<?> info = inbound;
            int start = frame.readerIndex();
            try {
                Packet<?> p = info.codec().decode(frame);
                if (frame.isReadable()) {
                    LOG.debug("{} left {} bytes unread in {}", p.type(), frame.readableBytes(), info.id().id());
                    frame.skipBytes(frame.readableBytes());
                }
                out.add(p);
            } catch (RuntimeException e) {
                frame.readerIndex(start);
                int id = new FriendlyByteBuf(frame).readVarInt();
                LOG.debug("Skipped {} packet {} the bot cannot decode: {}", info.id().id(), id, e.toString());
                frame.skipBytes(frame.readableBytes());
            }
        }
    }

    final class Encoder extends MessageToByteEncoder<Packet<?>> {
        @Override
        @SuppressWarnings("unchecked")
        protected void encode(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out) {
            // The codec of the current outbound protocol takes any packet of that protocol; the send side checked it.
            StreamCodec<ByteBuf, Packet<?>> codec = (StreamCodec<ByteBuf, Packet<?>>) (StreamCodec<?, ?>) outbound.codec();
            codec.encode(out, packet);
        }
    }
}
