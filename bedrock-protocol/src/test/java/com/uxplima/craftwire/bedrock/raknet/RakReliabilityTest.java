package com.uxplima.craftwire.bedrock.raknet;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Two reliability layers joined by a simulated UDP link that loses, duplicates and reorders datagrams: what one
 * side sends reliable-ordered must come out of the other exactly once and in order.
 */
class RakReliabilityTest {
    private static final int MTU = 1400;

    /** A one-way link: datagrams wait in flight and leave together, shuffled on a link that reorders. */
    private static final class Link {
        final Random random;
        final double loss;
        final double duplicate;
        final boolean reorder;
        final List<ByteBuf> inFlight = new ArrayList<>();
        final List<Integer> sentSizes = new ArrayList<>();
        RakReliability to;

        Link(Random random, double loss, double duplicate, boolean reorder) {
            this.random = random;
            this.loss = loss;
            this.duplicate = duplicate;
            this.reorder = reorder;
        }

        void send(ByteBuf b) {
            sentSizes.add(b.readableBytes());
            if (random.nextDouble() < loss) {
                b.release();
                return;
            }
            if (random.nextDouble() < duplicate) inFlight.add(b.copy());
            inFlight.add(b);
        }

        void deliver(long now) {
            List<ByteBuf> batch = new ArrayList<>(inFlight);
            inFlight.clear();
            if (reorder) Collections.shuffle(batch, random);
            for (ByteBuf b : batch) {
                int flags = b.readUnsignedByte();
                to.receive(flags, b, now);
                b.release();
            }
        }
    }

    private static final class Pair {
        final Link aToB;
        final Link bToA;
        final List<byte[]> receivedByB = new ArrayList<>();
        final RakReliability a;
        final RakReliability b;

        Pair(long seed, double loss, double duplicate, boolean reorder) {
            Random r = new Random(seed);
            aToB = new Link(r, loss, duplicate, reorder);
            bToA = new Link(r, loss, duplicate, reorder);
            a = new RakReliability(MTU, aToB::send, m -> { });
            b = new RakReliability(MTU, bToA::send, receivedByB::add);
            aToB.to = b;
            bToA.to = a;
        }

        /** Ticks both sides every 10 ms (resending every 60, as RakClient does) until `done` or 30 simulated s. */
        void run(long from, BooleanSupplier done) {
            for (long now = from, tick = 0; now < from + 30_000 && !done.getAsBoolean(); now += 10, tick++) {
                aToB.deliver(now);
                bToA.deliver(now);
                a.tick(now, tick % 6 == 0);
                b.tick(now, tick % 6 == 0);
            }
        }
    }

    private static List<byte[]> messages(int n, long seed) {
        Random r = new Random(seed);
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // every tenth message is larger than the MTU, so it is split and joined back
            byte[] m = new byte[i % 10 == 0 ? 3000 + r.nextInt(6000) : 1 + r.nextInt(900)];
            r.nextBytes(m);
            m[0] = (byte) i;
            out.add(m);
        }
        return out;
    }

    private static void assertSameOrder(List<byte[]> sent, List<byte[]> received) {
        assertEquals(sent.size(), received.size(), "messages delivered");
        for (int i = 0; i < sent.size(); i++) assertArrayEquals(sent.get(i), received.get(i), "message " + i);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void orderedMessagesArriveOnceAndInOrderOverABadLink(long seed) {
        Pair p = new Pair(seed, 0.2, 0.1, true);
        List<byte[]> sent = messages(150, seed);
        for (byte[] m : sent) p.a.send(m, Frame.RELIABLE_ORDERED, 0);
        p.run(0, () -> p.receivedByB.size() >= sent.size() && p.a.unacknowledged() == 0);
        assertSameOrder(sent, p.receivedByB);
        assertEquals(0, p.a.unacknowledged(), "every datagram was acknowledged in the end");
    }

    @Test
    void aPerfectLinkNeedsNoResends() {
        Pair p = new Pair(9, 0, 0, false);
        List<byte[]> sent = messages(40, 9);
        for (byte[] m : sent) p.a.send(m, Frame.RELIABLE_ORDERED, 0);
        List<Integer> sizes = List.copyOf(p.aToB.sentSizes);
        p.run(0, () -> p.receivedByB.size() >= sent.size() && p.a.unacknowledged() == 0);
        assertSameOrder(sent, p.receivedByB);
        assertEquals(sizes.size(), p.aToB.sentSizes.size(), "nothing was sent twice");
        assertTrue(sizes.stream().allMatch(s -> s <= MTU - RakIds.UDP_OVERHEAD), "every datagram fits the MTU: " + sizes);
    }

    @Test
    void lostUnreliableMessagesAreNotResent() {
        Pair p = new Pair(3, 1.0, 0, false);
        p.a.send(new byte[] {1, 2, 3}, Frame.UNRELIABLE, 0);
        assertEquals(0, p.a.unacknowledged());
        p.run(0, () -> false);
        assertTrue(p.receivedByB.isEmpty());
    }

    @Test
    void aDuplicatedDatagramIsDeliveredOnce() {
        Pair p = new Pair(4, 0, 1.0, false);
        p.a.send(new byte[] {42}, Frame.RELIABLE_ORDERED, 0);
        p.run(0, () -> p.a.unacknowledged() == 0);
        assertEquals(1, p.receivedByB.size());
        assertArrayEquals(new byte[] {42}, p.receivedByB.get(0));
    }

    @Test
    void unorderedReliableMessagesAllArriveExactlyOnce() {
        Pair p = new Pair(11, 0.3, 0.2, true);
        List<byte[]> sent = messages(60, 11);
        for (byte[] m : sent) p.a.send(m, Frame.RELIABLE, 0);
        p.run(0, () -> p.receivedByB.size() >= sent.size() && p.a.unacknowledged() == 0);
        List<Integer> firstBytes = new ArrayList<>();
        for (byte[] m : p.receivedByB) firstBytes.add((int) m[0]);
        Collections.sort(firstBytes);
        List<Integer> expected = new ArrayList<>();
        for (byte[] m : sent) expected.add((int) m[0]);
        Collections.sort(expected);
        assertEquals(expected, firstBytes);
        assertTrue(p.receivedByB.stream().allMatch(m -> sent.stream().anyMatch(s -> Arrays.equals(s, m))));
    }
}
