package com.uxplima.craftwire.fabric.camera;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.camera.CameraOverride.Pose;
import org.junit.jupiter.api.Test;

class CameraMotionJsonTest {
    static CameraMotion parse(String json, Pose current) {
        return CameraMotionJson.parse(JsonParser.parseString(json).getAsJsonObject(), current);
    }

    static AgentError error(String json) {
        return assertThrows(AgentError.class, () -> parse(json, null));
    }

    @Test
    void readsAPathWithDefaults() {
        CameraMotion m = parse("{action:'path', keyframes:[{t:0,x:0,y:70,z:0,yaw:0,pitch:0},{t:2000,x:10,y:70,z:0,yaw:90,pitch:0}]}", null);
        assertEquals("path", m.kind());
        assertEquals(2000, m.durationMs());
        assertEquals(5, m.at(1000).x(), 1e-9);   // eased and smooth, but symmetric: halfway is halfway
    }

    @Test
    void anOrbitStartsWhereTheCameraIsUnlessToldOtherwise() {
        Pose here = new Pose(0, 80, -10, 0, 0);   // north of the center
        CameraMotion m = parse("{action:'orbit', center:{x:0,y:64,z:0}, radius:10, durationMs:4000}", here);
        Pose start = m.at(0);
        assertEquals(0, start.x(), 1e-9);
        assertEquals(-10, start.z(), 1e-9);
        assertEquals(69, start.y(), 1e-9, "default height is radius / 2");
        assertEquals(0, parse("{action:'orbit', center:{x:0,y:64,z:0}, radius:10, durationMs:4000, startAngle:0}", here).at(0).x(), 1e-9);
        assertEquals(10, parse("{action:'orbit', center:{x:0,y:64,z:0}, radius:10, durationMs:4000, startAngle:0}", here).at(0).z(), 1e-9);
    }

    @Test
    void mistakesComeBackAsInvalidParams() {
        assertEquals("INVALID_PARAMS", error("{action:'spin'}").code());
        assertEquals("INVALID_PARAMS", error("{action:'path'}").code());
        assertEquals("INVALID_PARAMS", error("{action:'path', keyframes:[{t:0,x:0,y:0}]}").code());
        AgentError order = error("{action:'path', keyframes:[{t:5,x:0,y:0,z:0,yaw:0,pitch:0},{t:5,x:1,y:0,z:0,yaw:0,pitch:0}]}");
        assertTrue(order.getMessage().contains("increasing"), order.getMessage());
        assertEquals("INVALID_PARAMS", error("{action:'orbit', center:{x:0,y:0,z:0}, radius:0, durationMs:100}").code());
        assertEquals("INVALID_PARAMS", error("{action:'orbit', radius:3, durationMs:100}").code());
    }
}
