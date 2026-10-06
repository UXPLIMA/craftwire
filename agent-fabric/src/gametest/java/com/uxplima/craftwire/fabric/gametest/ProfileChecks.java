package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** profile and trace on the client: the render thread, owners, frame rate, and mixin handlers credited to their mod. */
final class ProfileChecks {
    private ProfileChecks() {}

    static void run(ClientGameTestContext ctx) {
        JsonObject r = Calls.call(ctx, "profile.run", "{\"durationMs\":2000}").getAsJsonObject();
        check("Render thread".equals(r.get("thread").getAsString()), "samples the render thread: " + r);
        check(r.get("samples").getAsInt() > 10, "the render thread was sampled: " + r);
        check(r.getAsJsonArray("owners").size() > 0 && r.getAsJsonArray("hotMethods").size() > 0, "owners and hot methods: " + r);
        check(r.getAsJsonObject("fps").get("avg").getAsInt() > 0, "reports the frame rate: " + r);

        // Camera.update runs every frame and carries the agent's mixin handler (CameraMixin).
        JsonObject t = Calls.call(ctx, "trace.run", "{\"method\":\"net.minecraft.client.Camera\",\"durationMs\":1000}").getAsJsonObject();
        JsonObject handler = null;
        for (JsonElement e : t.getAsJsonArray("methods")) {
            JsonObject m = e.getAsJsonObject();
            if (m.get("method").getAsString().contains("applyOverride")) handler = m;
        }
        check(handler != null, "the mixin handler is traced with Camera's methods: " + t.getAsJsonArray("methods"));
        check("craftwire".equals(handler.get("owner").getAsString()), "a mixin handler belongs to the mod that injected it: " + handler);
        check(handler.get("invocations").getAsLong() > 5, "it ran every frame: " + handler);
    }
}
