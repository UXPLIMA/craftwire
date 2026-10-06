package com.uxplima.craftwire.paper.wait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

class WaitTest {
    @Test
    void completesWithTheValueThatMetTheConditionAndTheTimeItTook() {
        long[] now = {1000};
        Wait w = new Wait("block", 5000, () -> now[0]);
        now[0] = 1200;
        assertFalse(w.observe(false, new JsonPrimitive("minecraft:air")));
        assertFalse(w.done());
        now[0] = 1450;
        assertTrue(w.observe(true, new JsonPrimitive("minecraft:stone")));
        assertTrue(w.done());

        JsonObject r = w.result().join().getAsJsonObject();
        assertTrue(r.get("matched").getAsBoolean());
        assertEquals("block", r.get("condition").getAsString());
        assertEquals(450, r.get("elapsedMs").getAsLong());
        assertEquals("minecraft:stone", r.get("value").getAsString());
    }

    @Test
    void aTimeoutReportsTheLastObservedValue() {
        long[] now = {0};
        Wait w = new Wait("inventory", 300, () -> now[0]);
        w.observe(false, new JsonPrimitive(2));
        now[0] = 301;
        w.timeOut();

        JsonObject r = w.result().join().getAsJsonObject();
        assertFalse(r.get("matched").getAsBoolean());
        assertEquals(301, r.get("elapsedMs").getAsLong());
        assertEquals(2, r.get("last").getAsInt());
    }

    @Test
    void theFirstOutcomeWins() {
        long[] now = {0};
        Wait w = new Wait("event", 300, () -> now[0]);
        w.timeOut();
        assertTrue(w.observe(true, new JsonPrimitive("late")), "a finished wait reports done");
        assertFalse(w.result().join().getAsJsonObject().get("matched").getAsBoolean());
    }

    @Test
    void anErrorEndsTheWait() {
        Wait w = new Wait("expr", 300, () -> 0);
        w.fail(new IllegalStateException("bad script"));
        assertTrue(w.done());
        assertTrue(w.result().isCompletedExceptionally());
    }

    @Test
    void scriptResultsAreTruthyLikeJavaScript() {
        assertFalse(Conditions.truthy(JsonNull.INSTANCE));
        assertFalse(Conditions.truthy(new JsonPrimitive(false)));
        assertFalse(Conditions.truthy(new JsonPrimitive(0)));
        assertFalse(Conditions.truthy(new JsonPrimitive("")));
        assertFalse(Conditions.truthy(new JsonPrimitive("NaN")));
        assertTrue(Conditions.truthy(new JsonPrimitive(true)));
        assertTrue(Conditions.truthy(new JsonPrimitive(3)));
        assertTrue(Conditions.truthy(new JsonPrimitive("open")));
        assertTrue(Conditions.truthy(new JsonArray()));
        assertTrue(Conditions.truthy(new JsonObject()));
    }
}
