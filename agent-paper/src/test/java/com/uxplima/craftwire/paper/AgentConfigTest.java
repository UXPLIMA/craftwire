package com.uxplima.craftwire.paper;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.core.AgentError;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class AgentConfigTest {
    @Test
    void defaultsAllowEverythingAndUseTheFolderName() {
        AgentConfig c = AgentConfig.from(new YamlConfiguration(), "myserver");
        assertEquals(new AgentConfig("myserver", true, true, true, 1_000_000, true), c);
    }

    @Test
    void readsValuesFromTheFile() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("instance-name: lobby\nallow-eval: false\nmax-edit-volume: 5000\n");
        AgentConfig c = AgentConfig.from(y, "ignored");
        assertEquals("lobby", c.instanceName());
        assertFalse(c.allowEval());
        assertTrue(c.allowWorldEdit());
        assertEquals(5000, c.maxEditVolume());
    }

    @Test
    void shippedConfigMatchesTheDefaults() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(new String(getClass().getResourceAsStream("/config.yml").readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(new AgentConfig("x", true, true, true, 1_000_000, true), AgentConfig.from(y, "x"));
    }

    @Test
    void aDisabledCapabilityIsPermissionDisabled() {
        AgentConfig c = new AgentConfig("s", false, true, true, 10, true);
        AgentError e = assertThrows(AgentError.class, () -> c.require(c.allowEval(), "allow-eval"));
        assertEquals("PERMISSION_DISABLED", e.code());
        assertTrue(e.hint().contains("allow-eval: true"), e.hint());
    }
}
