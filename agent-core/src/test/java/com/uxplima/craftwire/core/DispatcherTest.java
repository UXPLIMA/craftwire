package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DispatcherTest {
    private final Dispatcher dispatcher = new Dispatcher(new OperationCache(300_000, System::currentTimeMillis));

    private static AgentError errorOf(CompletableFuture<?> f) {
        CompletionException e = assertThrows(CompletionException.class, f::join);
        return assertInstanceOf(AgentError.class, e.getCause());
    }

    @Test
    void routesToHandler() {
        dispatcher.register("echo", p -> CompletableFuture.completedFuture(p));
        JsonObject params = new JsonObject();
        params.addProperty("a", 1);
        assertEquals(params, dispatcher.dispatch("echo", params).join());
    }

    @Test
    void unknownMethod() {
        assertEquals("UNKNOWN_METHOD", errorOf(dispatcher.dispatch("nope", new JsonObject())).code());
    }

    @Test
    void pausedRejectsEverything() {
        dispatcher.register("echo", p -> CompletableFuture.completedFuture(p));
        dispatcher.setPaused(true);
        AgentError err = errorOf(dispatcher.dispatch("echo", new JsonObject()));
        assertEquals("PAUSED_BY_USER", err.code());
        assertTrue(err.hint().contains("F8"));
        dispatcher.setPaused(false);
        assertNotNull(dispatcher.dispatch("echo", new JsonObject()).join());
    }

    @Test
    void synchronousThrowBecomesFailedFuture() {
        dispatcher.register("boom", p -> { throw new AgentError("NO_SCREEN_OPEN", "none", "open one"); });
        assertEquals("NO_SCREEN_OPEN", errorOf(dispatcher.dispatch("boom", new JsonObject())).code());
    }

    @Test
    void operationIdDeduplicates() {
        AtomicInteger runs = new AtomicInteger();
        dispatcher.register("count", p -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())));
        JsonObject params = new JsonObject();
        params.addProperty("operationId", "op-1");
        dispatcher.dispatch("count", params).join();
        assertEquals(1, dispatcher.dispatch("count", params).join().getAsInt());
        assertEquals(1, runs.get());
    }
}
