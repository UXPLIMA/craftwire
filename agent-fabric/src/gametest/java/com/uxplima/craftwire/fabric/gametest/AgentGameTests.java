package com.uxplima.craftwire.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class AgentGameTests implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getConnection().waitForChunksRender();
            LifecycleChecks.run(ctx, sp);
            StateChecks.run(ctx, sp);
            HudChecks.run(ctx, sp);
            GuiChecks.run(ctx, sp);
            try {
                CaptureChecks.run(ctx, sp);
                CameraRenderChecks.run(ctx, sp);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            InputSettingsChecks.run(ctx, sp);
            ExtensionChecks.run(ctx);
        }
    }
}
