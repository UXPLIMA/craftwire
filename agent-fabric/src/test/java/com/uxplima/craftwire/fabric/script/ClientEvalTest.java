package com.uxplima.craftwire.fabric.script;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.core.AgentError;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientEvalTest {
    @TempDir Path dir;

    @Test
    void onUnlessTheUserTurnsItOff() throws Exception {
        Path file = dir.resolve("craftwire.properties");
        assertTrue(ClientEval.allowed(null, file), "no config file");
        Files.writeString(file, "allow-eval=false\n");
        assertFalse(ClientEval.allowed(null, file));
        assertTrue(ClientEval.allowed("true", file), "the system property wins");
    }

    @Test
    void aConfigFileThatCannotBeReadKeepsItOff() throws Exception {
        Path file = dir.resolve("craftwire.properties");
        Files.writeString(file, "allow-eval=\\uZZZZ\n");   // a broken escape: the file cannot be parsed
        AgentError e = assertThrows(AgentError.class, () -> ClientEval.allowed(null, file));
        assertEquals("EVAL_DISABLED", e.code());
        assertTrue(e.getMessage().contains("craftwire.properties"), e.getMessage());
    }
}
