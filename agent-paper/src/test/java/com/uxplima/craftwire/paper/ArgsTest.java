package com.uxplima.craftwire.paper;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArgsTest {
    final JsonObject p = JsonParser.parseString("{\"s\":\"hi\",\"n\":3,\"o\":{\"x\":1},\"nil\":null}").getAsJsonObject();

    @Test
    void readsPresentValues() {
        assertEquals("hi", Args.string(p, "s"));
        assertEquals(3, Args.integer(p, "n"));
        assertEquals(1, Args.integer(Args.object(p, "o"), "x"));
        assertEquals(Optional.of(3L), Args.optLong(p, "n"));
    }

    @Test
    void nullCountsAsMissing() {
        assertEquals(Optional.empty(), Args.optString(p, "nil"));
        assertTrue(Args.bool(p, "nil", true));
    }

    @Test
    void missingRequiredValuesAreInvalidParams() {
        AgentError e = assertThrows(AgentError.class, () -> Args.string(p, "absent"));
        assertEquals("INVALID_PARAMS", e.code());
        assertTrue(e.getMessage().contains("absent"));
        assertThrows(AgentError.class, () -> Args.object(p, "s"));
    }
}
