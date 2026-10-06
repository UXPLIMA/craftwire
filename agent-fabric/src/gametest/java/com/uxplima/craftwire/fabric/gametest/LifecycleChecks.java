package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.uxplima.craftwire.fabric.CraftwireAgent;
import com.uxplima.craftwire.fabric.CraftwireClient;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import com.uxplima.craftwire.fabric.compat.Versions;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

final class LifecycleChecks {
    private LifecycleChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        CraftwireAgent agent = CraftwireClient.agent();

        // client_process starts clients with -Dcraftwire.hidden=true; the game tests run that way too (build.gradle), so
        // every capture check below also proves that a hidden window still renders.
        boolean hidden = Boolean.getBoolean("craftwire.hidden");
        boolean visible = ctx.computeOnClient(mc -> ClientCompat.get().windowVisible(mc.getWindow()));
        check(visible != hidden, "window visible=" + visible + " but craftwire.hidden=" + hidden);

        // The version-specific code matches the running game (this source compiles once per version).
        String running = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        check(running.startsWith(Versions.selected()), "compat " + Versions.selected() + " selected for Minecraft " + running);

        // Review Focus #1: a connected hub must stop the game from pausing when Claude Code takes focus.
        ctx.runOnClient(mc -> mc.options.pauseOnLostFocus = true);
        ctx.runOnClient(mc -> agent.onHubConnected("client-1"));
        ctx.waitTicks(2);
        check(!ctx.computeOnClient(mc -> mc.options.pauseOnLostFocus), "pauseOnLostFocus should be off while connected");
        ctx.runOnClient(mc -> agent.onHubDisconnected());
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> mc.options.pauseOnLostFocus), "pauseOnLostFocus should be restored on disconnect");

        // Client log lines are captured from mod start, so the hub's `logs` tool can read them after a connect.
        CraftwireAgent.LOGGER.info("craftwire gametest marker");
        check(agent.logs() != null && agent.logs().backlog().stream()
                        .anyMatch(e -> e.data().get("message").getAsString().equals("craftwire gametest marker")),
                "client log lines should be captured");

        // F8 kill switch toggles the dispatcher.
        ctx.getInput().pressKey(InputConstants.KEY_F8);
        ctx.waitTicks(2);
        check(agent.dispatcher().isPaused(), "F8 should pause");
        check("PAUSED_BY_USER".equals(Calls.error(ctx, "player.state", "{}").code()), "paused calls must fail with PAUSED_BY_USER");
        ctx.getInput().pressKey(InputConstants.KEY_F8);
        ctx.waitTicks(2);
        check(!agent.dispatcher().isPaused(), "second F8 should resume");

        // F8 must also work while a menu is open; that is where the AI spends most of its time.
        ctx.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
        ctx.waitTicks(2);
        ctx.getInput().pressKey(InputConstants.KEY_F8);
        ctx.waitTicks(2);
        check(agent.dispatcher().isPaused(), "F8 should pause while a screen is open");
        ctx.getInput().pressKey(InputConstants.KEY_F8);
        ctx.waitTicks(2);
        check(!agent.dispatcher().isPaused(), "F8 should resume while a screen is open");
        ctx.setScreen(() -> null);
        ctx.waitTicks(2);
    }
}
