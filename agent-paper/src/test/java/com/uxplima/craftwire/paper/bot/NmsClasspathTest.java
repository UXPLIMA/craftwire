package com.uxplima.craftwire.paper.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NmsClasspathTest {
    @Test
    void serverInternalsAreOnTheCompileClasspath() throws Exception {
        // Loaded without initialising: no Minecraft bootstrap in unit tests.
        Class<?> c = Class.forName("net.minecraft.server.level.ServerPlayer", false, getClass().getClassLoader());
        assertEquals("ServerPlayer", c.getSimpleName());
    }
}
