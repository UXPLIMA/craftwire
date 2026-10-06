package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/** client.quit: client_process stops the clients it started this way, so the game saves its options and exits cleanly. */
final class QuitHandler {
    private QuitHandler() {}

    static JsonElement quit() {
        Minecraft.getInstance().stop();   // only flags the main loop; the answer still goes out before the game exits
        JsonObject o = new JsonObject();
        o.addProperty("quitting", true);
        return o;
    }
}
