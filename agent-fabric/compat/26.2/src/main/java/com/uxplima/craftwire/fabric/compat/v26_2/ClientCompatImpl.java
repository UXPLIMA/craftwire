package com.uxplima.craftwire.fabric.compat.v26_2;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
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
}
