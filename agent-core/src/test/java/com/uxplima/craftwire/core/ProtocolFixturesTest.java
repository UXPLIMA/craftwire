package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProtocolFixturesTest {
    static final Path FIXTURES = Path.of("..", "protocol", "fixtures");

    @Test
    void everyValidFixtureIsWellFormedJsonRpc() throws Exception {
        List<Path> valid;
        try (Stream<Path> s = Files.list(FIXTURES)) {
            valid = s.filter(p -> p.getFileName().toString().startsWith("valid-")).toList();
        }
        assertFalse(valid.isEmpty());
        for (Path p : valid) {
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            assertEquals("2.0", o.get("jsonrpc").getAsString(), p.toString());
            assertTrue(o.has("method") || o.has("id"), p.toString());
        }
    }

    @Test
    void helloMatchesFixtureExactly() throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(FIXTURES.resolve("valid-hello.json"))).getAsJsonObject();
        String encoded = RpcCodec.hello("a".repeat(64), new Hello("client", "0.1.0", "26.2", "Sirac"));
        assertEquals(fixture, JsonParser.parseString(encoded));
    }

    @Test
    void errorMatchesFixtureShape() throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(FIXTURES.resolve("valid-response-error.json"))).getAsJsonObject();
        JsonObject data = fixture.getAsJsonObject("error").getAsJsonObject("data");
        String encoded = RpcCodec.error(fixture.get("id"),
                new AgentError(data.get("code").getAsString(), fixture.getAsJsonObject("error").get("message").getAsString(), data.get("hint").getAsString()));
        assertEquals(fixture, JsonParser.parseString(encoded));
    }
}
