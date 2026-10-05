package com.uxplima.craftwire.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface Handler {
    CompletableFuture<JsonElement> handle(JsonObject params);
}
