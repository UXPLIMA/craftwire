package com.uxplima.craftwire.script;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.uxplima.craftwire.core.AgentError;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScriptEngineTest {
    static ScriptEngine engine;   // engine startup takes about a second; share it

    @BeforeAll
    static void start() {
        engine = new ScriptEngine(ScriptEngineTest.class.getClassLoader(), "globalThis.greet = (n) => 'hi ' + n;");
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @BeforeEach
    void fresh() {
        engine.resetSession();
    }

    static JsonElement result(String code) {
        return engine.eval(code, 5000).get("result");
    }

    @Test
    void aResetRequestedDuringALongEvalDoesNotWaitForIt() throws Exception {
        result("globalThis.kept = 1");
        Thread running = new Thread(() -> engine.eval("const end = Date.now() + 1500; while (Date.now() < end) {} 'done'", 5000));
        running.start();
        Thread.sleep(200);   // the eval holds the engine now
        long t0 = System.nanoTime();
        engine.requestReset();   // the hub reconnected: called from the WebSocket thread
        long ms = (System.nanoTime() - t0) / 1_000_000;
        running.join();
        assertTrue(ms < 200, "requestReset must not wait for the running eval: " + ms + " ms");
        assertEquals("undefined", result("typeof kept").getAsString(), "the next eval starts a fresh session");
    }

    @Test
    void convertsJsValuesToJson() {
        assertEquals(JsonParser.parseString("{\"a\":1,\"b\":[true,\"x\",null],\"c\":null,\"d\":1.5}"),
                result("({a: 1, b: [true, 'x', null], c: undefined, d: 1.5})"));
    }

    @Test
    void summarisesJavaObjects() {
        JsonObject o = result("new (Java.type('java.lang.StringBuilder'))('hey')").getAsJsonObject();
        assertEquals("java.lang.StringBuilder", o.get("class").getAsString());
        assertEquals("hey", o.get("toString").getAsString());
    }

    @Test
    void javaListsBecomeArrays() {
        assertEquals(JsonParser.parseString("[1,2]"), result("Java.type('java.util.List').of(1, 2)"));
    }

    @Test
    void functionsAreMarked() {
        assertEquals(new JsonPrimitive("[function]"), result("(() => 1)"));
    }

    @Test
    void deepValuesAreCut() {
        JsonObject r = result("({a: {b: {c: {d: {e: 1}}}}})").getAsJsonObject();
        assertTrue(r.getAsJsonObject("a").getAsJsonObject("b").getAsJsonObject("c").get("d").isJsonPrimitive());
    }

    @Test
    void capturesPrintAndConsoleLog() {
        JsonObject r = engine.eval("print('a'); console.log('b'); 3", 5000);
        assertEquals(3, r.get("result").getAsInt());
        assertEquals("a\nb\n", r.get("output").getAsString().replace("\r\n", "\n"));
    }

    @Test
    void outputIsCapped() {
        String out = engine.eval("for (let i = 0; i < 20000; i++) print('xxxxxxxxxx'); 1", 5000).get("output").getAsString();
        assertTrue(out.length() <= CapturedOutput.LIMIT + 40, "length " + out.length());
        assertTrue(out.endsWith("(output truncated)"));
    }

    @Test
    void globalsPersistUntilReset() {
        result("globalThis.n = 41");
        assertEquals(42, result("n + 1").getAsInt());
        engine.resetSession();
        assertEquals("undefined", result("typeof n").getAsString());
    }

    @Test
    void preludeGlobalsExist() {
        assertEquals("hi Ada", result("greet('Ada')").getAsString());
    }

    @Test
    void infiniteLoopTimesOutAndResetsGlobals() {
        result("globalThis.k = 1");
        long t0 = System.nanoTime();
        AgentError e = assertThrows(AgentError.class, () -> engine.eval("while (true) {}", 300));
        assertEquals("TIMEOUT", e.code());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000);
        assertEquals("undefined", result("typeof k").getAsString());
    }

    @Test
    void theEvalRightAfterATimeoutGetsAFreshContext() {
        // The watchdog may still be closing the old context when the timed-out eval returns.
        for (int i = 0; i < 20; i++) {
            assertEquals("TIMEOUT", assertThrows(AgentError.class, () -> engine.eval("while (true) {}", 100)).code());
            assertEquals(2, result("1 + 1").getAsInt(), "iteration " + i);
        }
    }

    @Test
    void errorsReportLineAndColumn() {
        AgentError e = assertThrows(AgentError.class, () -> engine.eval("let a = 1;\nfoo.bar()", 5000));
        assertEquals("EVAL_ERROR", e.code());
        assertTrue(e.getMessage().contains("ReferenceError"), e.getMessage());
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void syntaxErrorsAreEvalErrors() {
        assertEquals("EVAL_ERROR", assertThrows(AgentError.class, () -> engine.eval("1 +", 5000)).code());
    }

    @Test
    void javaExceptionsNameTheJavaClass() {
        AgentError e = assertThrows(AgentError.class, () -> engine.eval("Java.type('java.lang.Integer').parseInt('x')", 5000));
        assertTrue(e.getMessage().contains("NumberFormatException"), e.getMessage());
    }
}
