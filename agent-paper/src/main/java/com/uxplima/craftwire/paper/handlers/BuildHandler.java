package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.world.render.WorldRenderHandler;
import java.util.concurrent.CompletableFuture;

final class BuildHandler {
    private BuildHandler() {}

    static CompletableFuture<JsonElement> run(JsonObject p, CraftwirePlugin plugin) {
        return plugin.builds().run(p).thenCompose(r -> WorldRenderHandler.attachPreview(p, r.getAsJsonObject(), plugin));
    }

    static CompletableFuture<JsonElement> manage(JsonObject p, CraftwirePlugin plugin) {
        return plugin.builds().manage(p);
    }
}
