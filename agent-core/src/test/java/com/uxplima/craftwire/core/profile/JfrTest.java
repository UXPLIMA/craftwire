package com.uxplima.craftwire.core.profile;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Real Flight Recorder runs against busy threads of this JVM. */
class JfrTest {
    static volatile long sink;

    static final class Busy {
        static long hot(int n) {
            long s = 0;
            for (int i = 0; i < n; i++) s += (i * 31L) ^ (s >>> 3);
            return s;
        }

        static long work(int n) {
            return hot(n);
        }
    }

    private static Thread busy(String name, AtomicBoolean stop, Runnable step) {
        Thread t = new Thread(() -> {
            while (!stop.get()) step.run();
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static final Owner TEST = new Owner("TestPlugin", Owner.PLUGIN);
    private static final OwnerIndex INDEX = OwnerIndex.builder()
            .addClasses(TEST, List.of(Busy.class.getName(), JfrTest.class.getName()))
            .build();

    @Test
    void profilesOnlyTheNamedThreadAndAttributesItsTime() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        busy("cw-busy", stop, () -> sink += Busy.work(200_000));
        busy("cw-other", stop, () -> sink += Busy.hot(200_000));
        try {
            Profiler.Session s = Profiler.start("cw-busy", 10, INDEX);
            Thread.sleep(1500);
            JsonObject r = s.finish(10);
            assertEquals("cw-busy", r.get("thread").getAsString());
            int samples = r.get("samples").getAsInt();
            assertTrue(samples >= 50, "samples: " + samples);
            JsonObject first = r.getAsJsonArray("owners").get(0).getAsJsonObject();
            assertEquals("TestPlugin", first.get("owner").getAsString());
            assertTrue(first.get("percent").getAsDouble() > 90, r.toString());
            assertTrue(r.get("durationMs").getAsLong() >= 1400);
        } finally {
            stop.set(true);
        }
    }

    @Test
    void takesTickTimesFromTheGame() throws Exception {
        Profiler.Session s = Profiler.start("nobody", 10, INDEX);
        long now = Profiler.now();
        s.tick(7, now, 60_000_000L);
        JsonObject r = s.finish(5);
        assertEquals(1, r.getAsJsonObject("ticks").get("count").getAsInt());
        assertEquals(1, r.getAsJsonObject("ticks").get("over50ms").getAsInt());
    }

    @Test
    void tracesCallsCountsAndCallersOfOneMethod() throws Exception {
        String filter = Busy.class.getName() + "::hot";
        MethodTracer.Session s = MethodTracer.start(filter, 0, 8, 5, INDEX);
        Thread caller = new Thread(() -> {
            for (int i = 0; i < 40; i++) sink += Busy.work(10_000);
        }, "cw-caller");
        caller.start();
        caller.join();
        Thread.sleep(300);
        JsonObject r = s.finish();

        JsonArray methods = r.getAsJsonArray("methods");
        assertEquals(1, methods.size(), r.toString());
        JsonObject m = methods.get(0).getAsJsonObject();
        assertEquals(Busy.class.getName() + ".hot", m.get("method").getAsString());
        assertEquals("TestPlugin", m.get("owner").getAsString());
        assertTrue(m.get("invocations").getAsLong() >= 40, r.toString());

        JsonArray slowest = r.getAsJsonArray("slowest");
        assertEquals(5, slowest.size());
        JsonObject call = slowest.get(0).getAsJsonObject();
        assertEquals("cw-caller", call.get("thread").getAsString());
        JsonArray stack = call.getAsJsonArray("stack");
        assertTrue(stack.size() <= 8);
        assertEquals(Busy.class.getName() + ".work", stack.get(0).getAsJsonObject().get("method").getAsString());

        JsonObject callers = r.getAsJsonArray("callers").get(0).getAsJsonObject();
        assertEquals(Busy.class.getName() + ".work", callers.get("method").getAsString());
        assertTrue(callers.get("count").getAsInt() >= 40);
        assertFalse(r.get("truncated").getAsBoolean());
    }

    @Test
    void aMethodThatNeverRanIsListedWithNoCalls() throws Exception {
        MethodTracer.Session s = MethodTracer.start(Busy.class.getName() + "::work", 0, 8, 5, INDEX);
        Thread.sleep(200);
        JsonObject r = s.finish();
        assertEquals(0, r.getAsJsonArray("slowest").size());
        for (JsonElement e : r.getAsJsonArray("methods")) assertEquals(0, e.getAsJsonObject().get("invocations").getAsLong());
    }

    @Test
    void rejectsFiltersThatAreNotAClassOrMethod() {
        for (String bad : new String[] {"", "com.x.Y::", "not a class", "com.x.Y::a::b", "x;y;z;w;v;u", "com.x.*"}) {
            AgentError e = assertThrows(AgentError.class, () -> MethodTracer.validate(bad), bad);
            assertEquals("INVALID_PARAMS", e.code());
        }
        for (String good : new String[] {"com.x.Y", "com.x.Y::m", "com.x.Outer$Inner::<init>", "@com.x.Ann", "com.x.Y::a;com.x.Z"}) {
            MethodTracer.validate(good);
        }
    }
}
