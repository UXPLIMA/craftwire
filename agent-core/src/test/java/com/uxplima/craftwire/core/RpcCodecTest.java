package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

class RpcCodecTest {
    private static JsonObject parse(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void illegalArgumentIsInvalidParams() {
        JsonObject o = parse(RpcCodec.error(new JsonPrimitive(3), new IllegalArgumentException("slot must be >= 0")));
        assertEquals("INVALID_PARAMS", o.getAsJsonObject("error").getAsJsonObject("data").get("code").getAsString());
        assertEquals("slot must be >= 0", o.getAsJsonObject("error").get("message").getAsString());
    }

    @Test
    void unknownThrowableIsInternal() {
        JsonObject o = parse(RpcCodec.error(new JsonPrimitive(3), new IllegalStateException("boom")));
        assertEquals("INTERNAL", o.getAsJsonObject("error").getAsJsonObject("data").get("code").getAsString());
    }

    @Test
    void nullResultIsJsonNull() {
        assertTrue(parse(RpcCodec.result(new JsonPrimitive(1), null)).get("result").isJsonNull());
    }

    @Test
    void eventHasTypeTimeData() {
        JsonObject data = new JsonObject();
        data.addProperty("text", "hi");
        JsonObject o = parse(RpcCodec.event("chat", data, 42L));
        assertEquals("event", o.get("method").getAsString());
        assertEquals(42L, o.getAsJsonObject("params").get("time").getAsLong());
        assertEquals("hi", o.getAsJsonObject("params").getAsJsonObject("data").get("text").getAsString());
    }
}
