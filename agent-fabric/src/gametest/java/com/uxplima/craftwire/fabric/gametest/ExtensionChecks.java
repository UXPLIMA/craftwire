package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

final class ExtensionChecks {
    private ExtensionChecks() {}

    static void run(ClientGameTestContext ctx) {
        JsonObject r = Calls.call(ctx, "ext.call", "{\"tool\":\"craftwire_agent_gametest_echo\",\"args\":{\"n\":7}}").getAsJsonObject();
        check(r.getAsJsonObject("args").get("n").getAsInt() == 7, "echo returns its arguments: " + r);
        check(r.get("renderThread").getAsBoolean(), "a mod tool runs on the render thread by default: " + r);
        check("EXTENSION_NOT_FOUND".equals(Calls.error(ctx, "ext.call", "{\"tool\":\"nobody_nothing\",\"args\":{}}").code()), "unknown tool");
    }
}
