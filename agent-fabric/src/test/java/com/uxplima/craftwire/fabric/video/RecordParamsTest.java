package com.uxplima.craftwire.fabric.video;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.camera.CameraMotion;
import com.uxplima.craftwire.fabric.camera.CameraMotionJson;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RecordParamsTest {
    static RecordParams parse(String json) {
        var p = JsonParser.parseString(json).getAsJsonObject();
        CameraMotion motion = p.has("camera") ? CameraMotionJson.parse(p.getAsJsonObject("camera"), null) : null;
        return RecordParams.parse(p, motion);
    }

    static AgentError error(String json) {
        return assertThrows(AgentError.class, () -> parse(json));
    }

    @Test
    void defaults() {
        RecordParams r = parse("{savePath:'clips/promo.mp4'}");
        assertEquals(Path.of("clips/promo.mp4").toAbsolutePath(), r.savePath());
        assertEquals("high", r.settings().preset());
        assertFalse(r.hud());
        assertTrue(r.chat());
        assertNull(r.durationMs());
        assertEquals(120, r.maxSeconds());
        assertTrue(r.waitForTerrain());
        assertFalse(r.waitForEnd());
        assertNull(r.motion());
    }

    @Test
    void theCameraMotionSetsTheLengthUnlessADurationIsGiven() {
        String orbit = "camera:{action:'orbit', center:{x:0,y:64,z:0}, radius:10, durationMs:8000}";
        assertEquals(8000L, parse("{savePath:'a.mp4', " + orbit + "}").durationMs());
        assertEquals(3000L, parse("{savePath:'a.mp4', durationMs:3000, " + orbit + "}").durationMs());
        assertNotNull(parse("{savePath:'a.mp4', " + orbit + "}").motion());
    }

    @Test
    void waitNeedsAnEnd() {
        assertTrue(error("{savePath:'a.mp4', wait:true}").getMessage().contains("durationMs"));
        assertTrue(parse("{savePath:'a.mp4', wait:true, durationMs:1000}").waitForEnd());
    }

    @Test
    void mistakesAreRefusedBeforeAnythingStarts() {
        assertEquals("INVALID_PARAMS", error("{}").code());
        assertTrue(error("{savePath:'a.avi'}").getMessage().contains(".mp4"));
        assertEquals("INVALID_PARAMS", error("{savePath:'a.mp4', durationMs:50}").code());
        assertEquals("INVALID_PARAMS", error("{savePath:'a.mp4', maxSeconds:601}").code());
        assertEquals("INVALID_PARAMS", error("{savePath:'a.mp4', maxSeconds:10, durationMs:20000}").code());
        assertEquals("INVALID_PARAMS", error("{savePath:'a.mp4', preset:'ultra'}").code());
    }

    @Test
    void anUppercaseExtensionIsFine() {
        assertEquals("CLIP.MP4", parse("{savePath:'CLIP.MP4'}").savePath().getFileName().toString());
    }
}
