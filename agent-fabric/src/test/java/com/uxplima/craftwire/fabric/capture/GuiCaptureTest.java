package com.uxplima.craftwire.fabric.capture;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GuiCaptureTest {
    static GuiCapture.Options parse(String json, String save) {
        return GuiCapture.Options.parse(JsonParser.parseString(json).getAsJsonObject(), save);
    }

    @Test
    void worldOrNoLayerMeansAnOrdinaryScreenshot() {
        assertNull(parse("{}", null));
        assertNull(parse("{\"layer\":\"world\"}", "a.jpg"));
    }

    @Test
    void transparentLayersRefuseJpegAndBadCombinations() {
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> parse("{\"layer\":\"gui\"}", "a.jpg")).code());
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> parse("{\"layer\":\"gui\",\"split\":true}", "a.png")).code());
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> parse("{\"layer\":\"gui\",\"guiScale\":9}", null)).code());
        GuiCapture.Options o = parse("{\"layer\":\"gui+tooltip\",\"split\":true,\"guiScale\":4}", "a.png");
        assertEquals(GuiLayer.GUI_TOOLTIP, o.layer());
        assertTrue(o.split() && o.trim());
        assertEquals(4, o.guiScale());
    }

    @Test
    void splitFilesAreNamedAfterTheirPart() {
        assertEquals(Path.of("d", "shot.gui.png"), GuiCapture.splitPath(Path.of("d", "shot.png"), "gui"));
        assertEquals(Path.of("d", "clip.tooltip.mov"), GuiCapture.splitPath(Path.of("d", "clip.mov"), "tooltip"));
    }
}
