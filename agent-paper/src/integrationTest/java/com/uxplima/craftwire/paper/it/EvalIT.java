package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class EvalIT {
    static JsonObject eval(String paramsJson) throws Exception {
        return ItEnv.get().hub.result("server.eval", paramsJson).getAsJsonObject();
    }

    @Test
    void seesTheBukkitApi() throws Exception {
        assertEquals(System.getProperty("craftwire.mcVersion"), eval("{\"code\":\"server.getMinecraftVersion()\"}").get("result").getAsString());
    }

    @Test
    void locHelperUsesTheMainWorld() throws Exception {
        assertEquals("world", eval("{\"code\":\"loc(0, 64, 0).getWorld().getName()\"}").get("result").getAsString());
    }

    @Test
    void printOutputIsReturned() throws Exception {
        JsonObject r = eval("{\"code\":\"print('hello from js'); 7\"}");
        assertEquals(7, r.get("result").getAsInt());
        assertTrue(r.get("output").getAsString().contains("hello from js"));
    }

    @Test
    void runawayScriptIsCancelledAndTheServerKeepsTicking() throws Exception {
        ItHub hub = ItEnv.get().hub;
        assertEquals("TIMEOUT", hub.error("server.eval", "{\"code\":\"while(true){}\",\"timeoutMs\":500}").get("code").getAsString());
        assertTrue(hub.result("server.info", "{}").getAsJsonObject().has("tps"));   // the server thread is free again
    }

    @Test
    void scriptErrorsAreEvalErrors() throws Exception {
        assertEquals("EVAL_ERROR", ItEnv.get().hub.error("server.eval", "{\"code\":\"nope()\"}").get("code").getAsString());
    }

    @Test
    void atRunsOnTheOwningRegion() throws Exception {
        assertEquals(2, eval("{\"code\":\"1 + 1\",\"at\":{\"x\":100,\"z\":-100}}").get("result").getAsInt());
    }

    @Test
    void resetClearsGlobals() throws Exception {
        eval("{\"code\":\"globalThis.q = 5\"}");
        assertEquals("undefined", eval("{\"code\":\"typeof q\",\"reset\":true}").get("result").getAsString());
    }

    @Test
    void globalsResetWhenTheHubReconnects() throws Exception {
        ItHub hub = ItEnv.get().hub;
        eval("{\"code\":\"globalThis.r = 1\"}");
        hub.dropConnectionAndClearEvents();
        hub.awaitHello(30_000, () -> true);
        assertEquals("undefined", eval("{\"code\":\"typeof r\"}").get("result").getAsString());
    }
}
