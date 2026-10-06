package com.uxplima.craftwire.fabric.compat;

import com.mojang.blaze3d.platform.Window;

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

    static ClientCompat get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        private static final ClientCompat INSTANCE = Versions.load(ClientCompat.class, "ClientCompatImpl");

        private Holder() {}
    }
}
