package com.uxplima.craftwire.fabric.gametest;

import com.uxplima.craftwire.api.CraftwireEntrypoint;
import com.uxplima.craftwire.api.CraftwireTool;
import com.uxplima.craftwire.api.ToolRegistry;
import net.minecraft.client.Minecraft;

/** A mod's tools, added through the "craftwire" entrypoint like any mod would. */
public final class GametestTools implements CraftwireEntrypoint {
    @Override
    public void registerTools(ToolRegistry registry) {
        registry.register(new CraftwireTool() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "Returns its arguments and whether it ran on the render thread."; }
            @Override public String call(String arguments) {
                return "{\"args\":" + arguments + ",\"renderThread\":" + Minecraft.getInstance().isSameThread() + "}";
            }
        });
    }
}
