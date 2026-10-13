package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The speed target: a million blocks in at most 10 s while the server keeps 20 TPS. Runs only with -Pperf. */
@Tag("perf")
class BuildPerfIT {
    @Test
    void millionBlocksInTenSecondsWithoutLag() throws Exception {
        ItHub hub = ItEnv.get().hub;
        List<Double> tps = new ArrayList<>(), mspt = new ArrayList<>();
        long started = System.currentTimeMillis();
        CompletableFuture<JsonObject> run = hub.send("build.run",
                "{\"project\":\"perf\",\"origin\":{\"x\":8000,\"y\":-60,\"z\":8000},\"code\":\"fill(0,0,0,99,99,99,'stone')\",\"timeoutMs\":300000}");
        while (!run.isDone()) {
            Thread.sleep(500);
            JsonObject info = hub.result("server.info", "{}").getAsJsonObject();
            tps.add(info.getAsJsonArray("tps").get(0).getAsDouble());
            mspt.add(info.get("mspt").getAsDouble());
        }
        long ms = System.currentTimeMillis() - started;
        JsonObject r = run.get(1, TimeUnit.SECONDS);
        assertFalse(r.has("error"), () -> "build.run failed: " + r.get("error"));
        assertEquals(1_000_000, r.getAsJsonObject("result").get("changed").getAsInt());
        System.out.println("[perf] 1M blocks in " + ms + " ms, tps " + tps + ", mspt " + mspt);
        hub.call("build.manage", "{\"action\":\"clear\",\"project\":\"perf\"}", 300_000);
        assertTrue(ms <= 10_000, "took " + ms + " ms");
        // tps[0] is a one-minute average and barely moves in 10 s; mspt (the last 100 ticks) is what shows lag.
        assertTrue(tps.stream().allMatch(t -> t >= 18.0), "tps " + tps);
        assertTrue(mspt.stream().allMatch(t -> t <= 50.0), "mspt " + mspt);
    }
}
