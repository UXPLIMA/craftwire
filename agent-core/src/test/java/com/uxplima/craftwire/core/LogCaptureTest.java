package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LogCaptureTest {
    final Logger log = LogManager.getLogger("craftwire-test");
    LogCapture capture;

    @AfterEach
    void tearDown() {
        if (capture != null) capture.uninstall();
    }

    static List<String> messages(List<LogCapture.Entry> entries) {
        return entries.stream()
                .filter(e -> e.data().get("logger").getAsString().equals("craftwire-test"))
                .map(e -> e.data().get("message").getAsString())
                .toList();
    }

    @Test
    void keepsInfoAndAboveWithLevelAndLogger() {
        capture = LogCapture.install(10);
        log.debug("quiet");
        log.info("hello");
        log.warn("careful");
        List<LogCapture.Entry> b = capture.backlog();
        assertEquals(List.of("hello", "careful"), messages(b));
        assertEquals("INFO", b.get(0).data().get("level").getAsString());
        assertEquals("WARN", b.get(1).data().get("level").getAsString());
        assertTrue(b.get(0).time() > 0);
    }

    @Test
    void backlogIsBounded() {
        capture = LogCapture.install(3);
        for (int i = 0; i < 5; i++) log.info("m{}", i);
        assertEquals(List.of("m2", "m3", "m4"), messages(capture.backlog()));
    }

    @Test
    void attachReplaysBacklogThenStreamsUntilDetached() {
        capture = LogCapture.install(10);
        log.info("before");
        List<String> got = new ArrayList<>();
        capture.attach((d, t) -> got.add(d.get("message").getAsString()));
        log.info("during");
        capture.detach();
        log.info("after");
        assertEquals(List.of("before", "during"), got);
    }

    @Test
    void rendersThrownStackTraces() {
        capture = LogCapture.install(10);
        log.error("broke", new IllegalStateException("bad state"));
        JsonObject d = capture.backlog().get(0).data();
        assertEquals("ERROR", d.get("level").getAsString());
        assertTrue(d.get("thrown").getAsString().contains("IllegalStateException: bad state"), d.toString());
    }

    @Test
    void loggingFromInsideTheSinkDoesNotRecurse() {
        capture = LogCapture.install(10);
        List<String> got = new ArrayList<>();
        capture.attach((d, t) -> {
            got.add(d.get("message").getAsString());
            log.info("echo of {}", d.get("message").getAsString());
        });
        log.info("outer");
        assertEquals(List.of("outer"), got);
        assertEquals(List.of("outer"), messages(capture.backlog()));
    }

    @Test
    void aFailingSinkDoesNotBreakLogging() {
        capture = LogCapture.install(10);
        capture.attach((d, t) -> { throw new IllegalStateException("socket gone"); });
        assertDoesNotThrow(() -> log.info("still logged"));
        assertEquals(List.of("still logged"), messages(capture.backlog()));
    }
}
