package com.uxplima.craftwire.botrunner.net;

import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * A bad connection for one bot, from bot.spawn's netsim {latencyMs, jitterMs, lossPercent}: every packet each way
 * waits latency ± jitter. On TCP (Java) a lost packet arrives late by a retransmission timeout instead, as the
 * kernel would resend it; on UDP (Bedrock) it is dropped and RakNet resends it. Plain Java on purpose: the Java bot
 * runs with its version's own Netty, so each side wraps this in a handler built against its Netty.
 */
public record NetSim(int latencyMs, int jitterMs, double lossPercent) {
    /** TCP's minimum retransmission timeout (RFC 6298 allows 200 ms; Linux uses it): what one lost segment costs. */
    private static final int RETRANSMIT_MS = 200;

    public NetSim {
        if (latencyMs < 0 || latencyMs > 5000) throw BotErrors.invalid("netsim.latencyMs must be 0-5000");
        if (jitterMs < 0 || jitterMs > 5000) throw BotErrors.invalid("netsim.jitterMs must be 0-5000");
        if (lossPercent < 0 || lossPercent > 100) throw BotErrors.invalid("netsim.lossPercent must be 0-100");
    }

    /** The spawn request's netsim, or empty when it has none or all of it is zero. */
    public static Optional<NetSim> from(JsonObject raw) {
        Optional<JsonObject> o = Params.optObject(raw, "netsim");
        if (o.isEmpty()) return Optional.empty();
        JsonObject n = o.get();
        NetSim sim = new NetSim(Params.optInt(n, "latencyMs").orElse(0), Params.optInt(n, "jitterMs").orElse(0),
                Params.optNumber(n, "lossPercent").orElse(0.0));
        return sim.latencyMs == 0 && sim.jitterMs == 0 && sim.lossPercent == 0 ? Optional.empty() : Optional.of(sim);
    }

    /** Whether this packet is lost (UDP: drop it). */
    public boolean lose() {
        return lossPercent > 0 && ThreadLocalRandom.current().nextDouble(100) < lossPercent;
    }

    /** How long this packet waits; on a stream a lost segment costs a retransmission timeout and a second trip. */
    public long delayNanos(boolean stream) {
        long ms = latencyMs + (jitterMs == 0 ? 0 : ThreadLocalRandom.current().nextInt(-jitterMs, jitterMs + 1));
        if (stream && lose()) ms += RETRANSMIT_MS + latencyMs;
        return TimeUnit.MILLISECONDS.toNanos(Math.max(0, ms));
    }
}
