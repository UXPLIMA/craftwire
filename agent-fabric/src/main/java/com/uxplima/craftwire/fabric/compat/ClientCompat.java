package com.uxplima.craftwire.fabric.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * What differs between the supported Minecraft versions on the client. Each version has its implementation in
 * agent-fabric/compat/<mc> (compiled against that version) and the agent jar carries all of them.
 * Key and button codes live here because the game's constants are inlined at compile time and changed with
 * the switch from GLFW to SDL3 in 26.3.
 */
public interface ClientCompat {
    /** Key code of F8, the pause key. */
    int keyF8();

    /** Button code of the left mouse button, as screens receive it. */
    int mouseButtonLeft();

    /** Moves the system cursor to window pixel coordinates, but only while the window has focus. */
    void warpCursor(Window window, double x, double y);

    /** Whether the game window is shown on screen (false for clients started hidden). */
    boolean windowVisible(Window window);

    /**
     * Starts copying the colour texture of {@code target} to CPU memory without waiting for the GPU (video frames).
     * {@code done} runs on the render thread a frame or two later with the pixels: RGBA, rows bottom-up,
     * width × height × 4 bytes, valid only during the call. Read buffers are pooled until {@link #releaseReadBuffers}.
     */
    void readPixels(RenderTarget target, Consumer<ByteBuffer> done);

    /** Frees the pooled read buffers (end of a recording). Render thread. */
    void releaseReadBuffers();

    static ClientCompat get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        private static final ClientCompat INSTANCE = Versions.load(ClientCompat.class, "ClientCompatImpl");

        private Holder() {}
    }
}
