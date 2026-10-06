package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.api.CraftwireTool;
import com.uxplima.craftwire.api.ToolException;
import com.uxplima.craftwire.api.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExtensionToolsTest {
    record Tool(String name, String description, String inputSchema, Body body, boolean onGameThread) implements CraftwireTool {
        interface Body { String run(String args) throws Exception; }
        Tool(String name, Body body) { this(name, "Does " + name, "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}}}", body, true); }
        @Override public String call(String arguments) throws Exception { return body.run(arguments); }
    }

    final AtomicInteger gameThreadRuns = new AtomicInteger();
    final AtomicInteger changes = new AtomicInteger();
    final List<String> logged = new ArrayList<>();
    final ExtensionTools tools = new ExtensionTools(
            new ExtensionTools.GameThread() {
                @Override public <T> CompletableFuture<T> run(java.util.concurrent.Callable<T> work) {
                    gameThreadRuns.incrementAndGet();
                    try {
                        return CompletableFuture.completedFuture(work.call());
                    } catch (Exception e) {
                        return CompletableFuture.failedFuture(e);
                    }
                }
            },
            Runnable::run, changes::incrementAndGet, (msg, t) -> logged.add(msg));

    static JsonObject call(String tool, String args) {
        JsonObject p = new JsonObject();
        p.addProperty("tool", tool);
        p.add("args", JsonParser.parseString(args));
        return p;
    }

    @Test
    void listsToolsUnderTheirNamespaceWithTheirSchema() {
        ToolRegistry shop = tools.registryFor("My Shop!");
        shop.register(new Tool("give_coins", a -> "{}"));
        JsonArray list = tools.list();
        assertEquals(1, list.size());
        JsonObject t = list.get(0).getAsJsonObject();
        assertEquals("my_shop_give_coins", t.get("name").getAsString());
        assertEquals("my_shop", t.get("namespace").getAsString());
        assertEquals("Does give_coins", t.get("description").getAsString());
        assertEquals("integer", t.getAsJsonObject("inputSchema").getAsJsonObject("properties").getAsJsonObject("n").get("type").getAsString());
        assertEquals(1, changes.get());
    }

    @Test
    void callsAToolOnTheGameThreadAndReturnsItsJson() {
        tools.registryFor("shop").register(new Tool("double", a -> "{\"n\":" + JsonParser.parseString(a).getAsJsonObject().get("n").getAsInt() * 2 + "}"));
        JsonObject r = tools.call(call("shop_double", "{\"n\":21}")).join().getAsJsonObject();
        assertEquals(42, r.get("n").getAsInt());
        assertEquals(1, gameThreadRuns.get());
    }

    @Test
    void aToolCanRunOffTheGameThreadAndReturnPlainText() {
        tools.registryFor("shop").register(new Tool("hello", "Says hello", "{\"type\":\"object\"}", a -> "hello there", false));
        assertEquals("hello there", tools.call(call("shop_hello", "{}")).join().getAsString());
        assertEquals(0, gameThreadRuns.get());
    }

    @Test
    void toolExceptionsBecomeCraftwireErrorsAndBugsAreLogged() {
        ToolRegistry r = tools.registryFor("shop");
        r.register(new Tool("refuse", a -> { throw new ToolException("NOT_ENOUGH_COINS", "Steve has 3 coins", "Give coins first."); }));
        r.register(new Tool("crash", a -> { throw new IllegalStateException("boom"); }));

        AgentError refused = cause(() -> tools.call(call("shop_refuse", "{}")).join());
        assertEquals("NOT_ENOUGH_COINS", refused.code());
        assertEquals("Give coins first.", refused.hint());

        AgentError crashed = cause(() -> tools.call(call("shop_crash", "{}")).join());
        assertEquals("EXTENSION_FAILED", crashed.code());
        assertTrue(crashed.getMessage().contains("IllegalStateException: boom"), crashed.getMessage());
        assertEquals(1, logged.size());

        assertEquals("EXTENSION_NOT_FOUND", cause(() -> tools.call(call("shop_nope", "{}")).join()).code());
    }

    @Test
    void rejectsBadNamesAndSchemasWhenRegistering() {
        ToolRegistry r = tools.registryFor("shop");
        assertThrows(IllegalArgumentException.class, () -> r.register(new Tool("Give Coins", a -> "{}")));
        assertThrows(IllegalArgumentException.class, () -> r.register(new Tool("ok", "d", "[1]", a -> "{}", true)));
        assertThrows(IllegalArgumentException.class, () -> r.register(new Tool("ok", "d", "{\"type\":\"string\"}", a -> "{}", true)));
        assertThrows(IllegalArgumentException.class, () -> r.register(new Tool("x".repeat(41), a -> "{}")));
        assertEquals(0, tools.list().size());
    }

    @Test
    void replacesAndUnregisters() {
        ToolRegistry r = tools.registryFor("shop");
        Tool a = new Tool("t", x -> "1");
        Tool b = new Tool("t", x -> "2");
        r.register(a);
        r.register(b);
        assertEquals(1, tools.list().size());
        assertEquals(2, tools.call(call("shop_t", "{}")).join().getAsInt());
        r.unregister(a);
        assertEquals(1, tools.list().size(), "only the registered instance is removed");
        r.unregister(b);
        assertEquals(0, tools.list().size());
        tools.registryFor("other").register(new Tool("t", x -> "3"));
        tools.unregisterIf(t -> true);
        assertFalse(tools.list().iterator().hasNext());
    }

    static AgentError cause(Runnable r) {
        CompletionException e = assertThrows(CompletionException.class, r::run);
        return (AgentError) e.getCause();
    }
}
