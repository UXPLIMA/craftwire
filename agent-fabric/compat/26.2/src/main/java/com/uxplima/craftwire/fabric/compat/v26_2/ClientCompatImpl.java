package com.uxplima.craftwire.fabric.compat.v26_2;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.lwjgl.glfw.GLFW;

/** Minecraft 26.2: GLFW window and input. */
public final class ClientCompatImpl implements ClientCompat {
    @Override
    public int keyF8() {
        return InputConstants.KEY_F8;
    }

    @Override
    public int mouseButtonLeft() {
        return InputConstants.MOUSE_BUTTON_LEFT;
    }

    @Override
    public void warpCursor(Window window, double x, double y) {
        // GLFW ignores this for an unfocused window, so the user's cursor is never moved.
        GLFW.glfwSetCursorPos(window.handle(), x, y);
    }

    @Override
    public boolean windowVisible(Window window) {
        return GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE;
    }

    private final FrameReader frames = new FrameReader();

    @Override
    public void readPixels(RenderTarget target, Consumer<ByteBuffer> done) {
        frames.read(target, done);
    }

    @Override
    public void releaseReadBuffers() {
        frames.release();
    }
}
