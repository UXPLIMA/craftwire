package com.uxplima.craftwire.core.profile;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ProfileToolsTest {
    private final AtomicBoolean fed = new AtomicBoolean();
    private final ProfileTools tools = new ProfileTools(new ProfileTools.Platform() {
        @Override public String gameThread() { return "cw-game"; }
        @Override public OwnerIndex owners() { return OwnerIndex.builder().build(); }
        @Override public ProfileTools.Feed feed(Profiler.Session session) {
            fed.set(true);
            session.tick(1, Profiler.now(), 30_000_000L);
            return report -> report.addProperty("fps", 60);
        }
    });

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void profileRunsForTheDurationAndAddsWhatTheGameFeeds() {
        long start = System.nanoTime();
        JsonObject r = tools.profile(json("{\"durationMs\": 1000, \"top\": 3}")).join().getAsJsonObject();
        assertTrue((System.nanoTime() - start) / 1_000_000L >= 1000);
        assertTrue(fed.get());
        assertEquals("cw-game", r.get("thread").getAsString());
        assertEquals(60, r.get("fps").getAsInt());
        assertEquals(1, r.getAsJsonObject("ticks").get("count").getAsInt());
    }

    @Test
    void traceExplainsAFilterThatMatchedNothing() {
        JsonObject r = tools.trace(json("{\"method\": \"com.nowhere.Nothing::none\", \"durationMs\": 1000}")).join().getAsJsonObject();
        assertTrue(r.get("hint").getAsString().contains("No method matched"), r.toString());
    }

    @Test
    void traceRejectsABadFilterRightAway() {
        AgentError e = assertThrows(AgentError.class, () -> tools.trace(json("{\"method\": \"no good\"}")));
        assertEquals("INVALID_PARAMS", e.code());
        assertThrows(AgentError.class, () -> tools.trace(json("{}")));
    }

    @Test
    void closeStopsRunningSessions() {
        var f = tools.profile(json("{\"durationMs\": 60000}"));
        tools.close();
        assertThrows(CompletionException.class, f::join);
    }
}
