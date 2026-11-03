package com.uxplima.craftwire.botrunner.net;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** bot.spawn's netsim: parsing, and the delays and losses it hands the network handlers. */
class NetSimTest {
    private static JsonObject spawn(String netsim) {
        return JsonParser.parseString("{netsim:" + netsim + "}").getAsJsonObject();
    }

    private static long ms(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    @Test
    void noNetsimOrAllZeroMeansAPlainConnection() {
        assertTrue(NetSim.from(new JsonObject()).isEmpty());
        assertTrue(NetSim.from(spawn("{latencyMs:0}")).isEmpty());
        assertEquals(new NetSim(80, 0, 0), NetSim.from(spawn("{latencyMs:80}")).orElseThrow());
    }

    @Test
    void outOfRangeValuesAreRefused() {
        for (String bad : new String[] {"{latencyMs:-1}", "{jitterMs:9000}", "{lossPercent:101}"}) {
            assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> NetSim.from(spawn(bad)), bad).code());
        }
    }

    @Test
    void delaysStayWithinLatencyPlusMinusJitter() {
        NetSim sim = new NetSim(100, 30, 0);
        long min = Long.MAX_VALUE;
        long max = 0;
        for (int i = 0; i < 2000; i++) {
            long d = ms(sim.delayNanos(true));
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        assertTrue(min >= 70 && max <= 130, min + ".." + max);
        assertTrue(max - min > 40, "the jitter actually varies the delay: " + min + ".." + max);
    }

    @Test
    void aJitterLargerThanTheLatencyNeverGivesANegativeDelay() {
        NetSim sim = new NetSim(5, 50, 0);
        for (int i = 0; i < 1000; i++) assertTrue(sim.delayNanos(false) >= 0);
    }

    @Test
    void onAStreamALostPacketArrivesLateInsteadOfNever() {
        NetSim sim = new NetSim(50, 0, 100);
        assertTrue(sim.lose());
        // TCP resends after its 200 ms minimum timeout, and the resend travels the latency again
        assertEquals(50 + 200 + 50, ms(sim.delayNanos(true)));
        assertEquals(50, ms(sim.delayNanos(false)), "datagrams are dropped by the caller, not delayed");
    }

    @Test
    void lossFollowsThePercentage() {
        NetSim sim = new NetSim(0, 0, 25);
        int lost = 0;
        for (int i = 0; i < 20_000; i++) if (sim.lose()) lost++;
        assertEquals(25, lost / 200.0, 2.0);
        assertFalse(new NetSim(10, 0, 0).lose());
    }
}
