package com.uxplima.craftwire.core.profile;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FrameTest {
    @Test
    void lambdaClassesReadTheSameEveryRun() {
        assertEquals("com.x.Lag$$Lambda.accept", new Frame("com.x.Lag$$Lambda/0x0000000019529c00", "accept", "", -1).qualified());
        assertEquals("com.x.Lag$$Lambda.run", new Frame("com.x.Lag$$Lambda.0x000000003b1a48f0", "run", "", -1).qualified());
        assertEquals("com.x.Lag$$Lambda.run", new Frame("com.x.Lag$$Lambda$12", "run", "", -1).qualified());
        assertEquals("com.x.Lag.burn", new Frame("com.x.Lag", "burn", "", -1).qualified());
    }

    @Test
    void namesTheEventParameter() {
        assertEquals("PlayerMoveEvent", new Frame("a.B", "on", "(Lorg/bukkit/event/player/PlayerMoveEvent;)V", 1).eventParameter());
        assertNull(new Frame("a.B", "on", "(ILa/Outer$Inner;)V", 1).eventParameter());
        assertEquals("FooEvent", new Frame("a.B", "on", "(I[JLa/b/FooEvent;)V", 1).eventParameter());
        assertNull(new Frame("a.B", "on", "", 1).eventParameter());
    }
}
