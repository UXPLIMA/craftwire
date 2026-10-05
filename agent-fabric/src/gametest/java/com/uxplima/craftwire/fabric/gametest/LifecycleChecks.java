package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.uxplima.craftwire.fabric.CraftwireAgent;
import com.uxplima.craftwire.fabric.CraftwireClient;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.lwjgl.glfw.GLFW;

final class LifecycleChecks {
    private LifecycleChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        CraftwireAgent agent = CraftwireClient.agent();

        // Review Focus #1: a connected hub must stop the game from pausing when Claude Code takes focus.
        ctx.runOnClient(mc -> mc.options.pauseOnLostFocus = true);
        ctx.runOnClient(mc -> agent.onHubConnected("client-1"));
        ctx.waitTicks(2);
        check(!ctx.computeOnClient(mc -> mc.options.pauseOnLostFocus), "pauseOnLostFocus should be off while connected");
        ctx.runOnClient(mc -> agent.onHubDisconnected());
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> mc.options.pauseOnLostFocus), "pauseOnLostFocus should be restored on disconnect");

        // F8 kill switch toggles the dispatcher.
        ctx.getInput().pressKey(GLFW.GLFW_KEY_F8);
        ctx.waitTicks(2);
        check(agent.dispatcher().isPaused(), "F8 should pause");
        check("PAUSED_BY_USER".equals(Calls.error(ctx, "player.state", "{}").code()), "paused calls must fail with PAUSED_BY_USER");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_F8);
        ctx.waitTicks(2);
        check(!agent.dispatcher().isPaused(), "second F8 should resume");

        // F8 must also work while a menu is open; that is where the AI spends most of its time.
        ctx.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
        ctx.waitTicks(2);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_F8);
        ctx.waitTicks(2);
        check(agent.dispatcher().isPaused(), "F8 should pause while a screen is open");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_F8);
        ctx.waitTicks(2);
        check(!agent.dispatcher().isPaused(), "F8 should resume while a screen is open");
        ctx.setScreen(() -> null);
        ctx.waitTicks(2);
    }
}
