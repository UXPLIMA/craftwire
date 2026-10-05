package com.uxplima.craftwire.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class Dispatcher {
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final OperationCache cache;
    private volatile boolean paused;

    public Dispatcher(OperationCache cache) {
        this.cache = cache;
    }

    public void register(String method, Handler handler) {
        handlers.put(method, handler);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public CompletableFuture<JsonElement> dispatch(String method, JsonObject params) {
        if (paused) {
            return CompletableFuture.failedFuture(new AgentError("PAUSED_BY_USER",
                    "The player paused Craftwire control in-game.",
                    "Ask the user to press F8 in Minecraft to resume."));
        }
        Handler handler = handlers.get(method);
        if (handler == null) {
            return CompletableFuture.failedFuture(new AgentError("UNKNOWN_METHOD",
                    "Unknown method: " + method, "Update the Craftwire agent so it matches the hub version."));
        }
        Supplier<CompletableFuture<JsonElement>> run = () -> {
            try {
                return handler.handle(params);
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        };
        JsonElement op = params.get("operationId");
        return op != null && op.isJsonPrimitive() ? cache.run(op.getAsString(), run) : run.get();
    }
}
