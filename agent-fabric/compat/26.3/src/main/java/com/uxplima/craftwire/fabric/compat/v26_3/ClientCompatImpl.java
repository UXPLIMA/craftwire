package com.uxplima.craftwire.fabric.compat.v26_3;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;

/** Minecraft 26.3: SDL3 window and input (SDL scancodes and button numbers). */
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
        // SDL would also move the cursor of an unfocused window; only warp while the user is in the game.
        if (window.isFocused()) SDLMouse.SDL_WarpMouseInWindow(window.handle(), (float) x, (float) y);
    }

    @Override
    public boolean windowVisible(Window window) {
        return (SDLVideo.SDL_GetWindowFlags(window.handle()) & SDLVideo.SDL_WINDOW_HIDDEN) == 0;
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
