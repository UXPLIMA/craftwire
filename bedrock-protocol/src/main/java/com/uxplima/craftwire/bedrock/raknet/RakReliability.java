package com.uxplima.craftwire.bedrock.raknet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * RakNet's reliability layer for one connection: datagram sequence numbers, ACKs and NACKs, resending what the peer
 * did not acknowledge, splitting messages larger than the MTU and joining them back, dropping duplicates, and
 * handing ordered messages out in order. One frame per datagram on send; any number on receive. Event loop thread.
 */
final class RakReliability {
    private static final int MAX_SPLITS = 32;
    private static final int MAX_SPLIT_COUNT = 1024;
    private static final long MIN_RESEND_MS = 100;

    private record Sent(Frame frame, long at) {}

    private final int mtu;
    private final Consumer<ByteBuf> socket;
    private final Consumer<byte[]> deliver;
    private int datagramSeq;
    private int messageIndex;
    private int orderIndex;
    private int splitId;
    private final Map<Integer, Sent> unacked = new HashMap<>();
    private final List<Integer> pendingAcks = new ArrayList<>();
    private int highestReceived = -1;
    private final Set<Integer> receivedAbove = new HashSet<>();
    private int lowestMissing;
    private int lowestReliable;
    private final Set<Integer> reliableAbove = new HashSet<>();
    private int nextOrder;
    private final Map<Integer, byte[]> waiting = new HashMap<>();
    private final Map<Integer, byte[][]> splits = new HashMap<>();
    private double rttMs = 50;

    RakReliability(int mtu, Consumer<ByteBuf> socket, Consumer<byte[]> deliver) {
        this.mtu = mtu;
        this.socket = socket;
        this.deliver = deliver;
    }

    double rttMs() {
        return rttMs;
    }

    /** Sends a message with the given reliability, split into fragments that each fit one datagram. */
    void send(byte[] message, int reliability, long now) {
        int room = mtu - RakIds.UDP_OVERHEAD - Frame.OVERHEAD;
        boolean split = message.length > room;
        if (split) room -= Frame.SPLIT_OVERHEAD;
        int count = (message.length + room - 1) / room;
        int order = Frame.ordered(reliability) ? orderIndex++ : 0;
        int id = split ? splitId++ & 0xffff : 0;
        for (int i = 0; i < count; i++) {
            Frame f = new Frame();
            f.reliability = reliability;
            f.orderIndex = order;
            if (Frame.reliable(reliability)) f.messageIndex = messageIndex++;
            f.split = split;
            if (split) {
                f.splitCount = count;
                f.splitId = id;
                f.splitIndex = i;
            }
            int from = i * room;
            f.payload = Arrays.copyOfRange(message, from, Math.min(message.length, from + room));
            datagram(f, now);
        }
    }

    private void datagram(Frame f, long now) {
        ByteBuf b = Unpooled.buffer(4 + f.size());
        int seq = datagramSeq++ & 0xffffff;
        b.writeByte(RakIds.FLAG_DATAGRAM | RakIds.FLAG_NEEDS_B_AND_AS);
        b.writeMediumLE(seq);
        f.write(b);
        if (Frame.reliable(f.reliability)) unacked.put(seq, new Sent(f, now));
        socket.accept(b);
    }

    /** A datagram from the peer (the flags byte already read): ACK, NACK or frames. */
    void receive(int flags, ByteBuf b, long now) {
        if ((flags & RakIds.FLAG_ACK) != 0) {
            for (int seq : AckRanges.read(b)) {
                Sent s = unacked.remove(seq);
                if (s != null) rttMs = rttMs * 0.875 + (now - s.at()) * 0.125;
            }
        } else if ((flags & RakIds.FLAG_NACK) != 0) {
            for (int seq : AckRanges.read(b)) resend(seq, now);
        } else {
            int seq = b.readUnsignedMediumLE();
            if (!firstTime(seq)) return;
            pendingAcks.add(seq);
            while (b.isReadable()) frame(Frame.read(b));
        }
    }

    /** Tracks datagram sequence numbers; a gap is NACKed at once so the peer resends without waiting. */
    private boolean firstTime(int seq) {
        if (seq < lowestMissing || receivedAbove.contains(seq)) return false;
        if (seq > highestReceived + 1 && highestReceived >= 0) {
            List<Integer> missing = new ArrayList<>();
            for (int s = highestReceived + 1; s < seq && missing.size() < 512; s++) missing.add(s);
            sendAck(missing, RakIds.FLAG_NACK);
        }
        highestReceived = Math.max(highestReceived, seq);
        receivedAbove.add(seq);
        while (receivedAbove.remove(lowestMissing)) lowestMissing++;
        // Give up on very old holes: the peer resends lost reliable frames in new datagrams anyway.
        if (receivedAbove.size() > 4096) {
            lowestMissing = highestReceived + 1;
            receivedAbove.clear();
        }
        return true;
    }

    private void frame(Frame f) {
        if (Frame.reliable(f.reliability) && !firstReliable(f.messageIndex)) return;
        if (f.split) {
            joinSplit(f);
            return;
        }
        handOut(f, f.payload);
    }

    private boolean firstReliable(int index) {
        if (index < lowestReliable || reliableAbove.contains(index)) return false;
        reliableAbove.add(index);
        while (reliableAbove.remove(lowestReliable)) lowestReliable++;
        return true;
    }

    private void joinSplit(Frame f) {
        if (f.splitCount <= 0 || f.splitCount > MAX_SPLIT_COUNT || f.splitIndex < 0 || f.splitIndex >= f.splitCount) return;
        byte[][] parts = splits.get(f.splitId);
        if (parts == null) {
            if (splits.size() >= MAX_SPLITS) return;
            parts = new byte[f.splitCount][];
            splits.put(f.splitId, parts);
        }
        if (parts.length != f.splitCount) return;
        parts[f.splitIndex] = f.payload;
        int total = 0;
        for (byte[] p : parts) {
            if (p == null) return;
            total += p.length;
        }
        splits.remove(f.splitId);
        byte[] whole = new byte[total];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, whole, at, p.length);
            at += p.length;
        }
        handOut(f, whole);
    }

    /** Ordered messages wait for every earlier one; the rest go out as they come. */
    private void handOut(Frame f, byte[] message) {
        if (f.reliability != Frame.RELIABLE_ORDERED) {
            deliver.accept(message);
            return;
        }
        if (f.orderIndex < nextOrder) return;
        waiting.put(f.orderIndex, message);
        byte[] next;
        while ((next = waiting.remove(nextOrder)) != null) {
            nextOrder++;
            deliver.accept(next);
        }
    }

    /** Every tick: ACK what arrived, and resend what the peer has not acknowledged within 1.5 round trips. */
    void tick(long now, boolean resend) {
        if (!pendingAcks.isEmpty()) {
            sendAck(pendingAcks, RakIds.FLAG_ACK);
            pendingAcks.clear();
        }
        if (!resend) return;
        long delay = Math.max(MIN_RESEND_MS, (long) (rttMs * 1.5));
        List<Integer> late = new ArrayList<>();
        for (Iterator<Map.Entry<Integer, Sent>> it = unacked.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Sent> e = it.next();
            if (now - e.getValue().at() > delay) late.add(e.getKey());
        }
        for (int seq : late) resend(seq, now);
    }

    private void resend(int seq, long now) {
        Sent s = unacked.remove(seq);
        if (s != null) datagram(s.frame(), now);
    }

    private void sendAck(List<Integer> numbers, int flag) {
        List<Integer> left = new ArrayList<>(numbers);
        int room = mtu - RakIds.UDP_OVERHEAD - 1;
        while (!left.isEmpty()) {
            ByteBuf b = Unpooled.buffer(Math.min(room + 1, 16 + left.size() * 7));
            b.writeByte(flag | RakIds.FLAG_DATAGRAM);
            int n = AckRanges.write(b, left, room);
            if (n == 0) {
                b.release();
                return;
            }
            left.subList(0, n).clear();
            socket.accept(b);
        }
    }

    int unacknowledged() {
        return unacked.size();
    }
}
