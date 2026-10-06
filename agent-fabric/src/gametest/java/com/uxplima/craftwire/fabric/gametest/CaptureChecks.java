package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.CraftwireClient;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.phys.Vec3;

final class CaptureChecks {
    private CaptureChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) throws java.io.IOException {
        Path dir = Files.createTempDirectory("craftwire-shots");
        String save = dir.resolve("inv.png").toString().replace("\\", "\\\\");

        // Review Focus #4: capture with hud:false while a GUI is open; everything is restored afterwards.
        ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        ctx.waitTicks(2);
        JsonObject shot = Calls.call(ctx, "screenshot", "{\"hud\":false,\"maxSize\":512,\"savePath\":\"" + save + "\"}").getAsJsonObject();
        check(shot.get("mime").getAsString().startsWith("image/"), "mime");
        check(Math.max(shot.get("width").getAsInt(), shot.get("height").getAsInt()) <= 512, "downscaled: " + shot);
        check(shot.get("fullWidth").getAsInt() >= shot.get("width").getAsInt(), "full size");
        check(Files.size(Path.of(shot.get("savedPath").getAsString())) > 1000, "saved PNG should exist");
        check(!shot.get("data").getAsString().isEmpty(), "base64 data");
        check(Calls.call(ctx, "gui.read", "{}").getAsJsonObject().get("open").getAsBoolean(), "GUI must stay open");
        check(!Calls.call(ctx, "hud.read", "{}").getAsJsonObject().get("hidden").getAsBoolean(), "HUD must be restored");
        check(!CraftwireClient.agent().isCaptureInProgress(), "capture flag cleared");

        // Failure path: an unwritable savePath still restores state.
        Path blocker = Files.createFile(dir.resolve("blocker"));
        String bad = blocker.resolve("x.png").toString().replace("\\", "\\\\");
        Calls.error(ctx, "screenshot", "{\"hud\":false,\"savePath\":\"" + bad + "\"}");
        check(!Calls.call(ctx, "hud.read", "{}").getAsJsonObject().get("hidden").getAsBoolean(), "HUD restored after failure");
        check(!CraftwireClient.agent().isCaptureInProgress(), "capture flag cleared after failure");
        // A frame grab that never completes (e.g. minimised window) times out and still restores state.
        com.uxplima.craftwire.fabric.CaptureFaults.grabTimeoutMillis = 300;
        com.uxplima.craftwire.fabric.CaptureFaults.stallNextGrab = true;
        var stalled = Calls.error(ctx, "screenshot", "{\"hud\":false}");
        com.uxplima.craftwire.fabric.CaptureFaults.grabTimeoutMillis = 10_000;
        check("SCREENSHOT_FAILED".equals(stalled.code()), "stalled grab code: " + stalled.code());
        check(!Calls.call(ctx, "hud.read", "{}").getAsJsonObject().get("hidden").getAsBoolean(), "HUD restored after stalled grab");
        check(!CraftwireClient.agent().isCaptureInProgress(), "capture flag cleared after stalled grab");
        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");

        // Camera override moves only the render camera.
        Vec3 eye = ctx.computeOnClient(mc -> mc.player.getEyePosition());
        Calls.call(ctx, "camera", String.format(java.util.Locale.ROOT,
                "{\"action\":\"set\",\"x\":%f,\"y\":%f,\"z\":%f,\"yaw\":0,\"pitch\":45}", eye.x, eye.y + 10, eye.z));
        ctx.waitTicks(2);
        Vec3 cam = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position());
        check(Math.abs(cam.y - (eye.y + 10)) < 0.01, "camera y should be overridden: " + cam);
        check(ctx.computeOnClient(mc -> mc.player.getEyePosition()).distanceTo(eye) < 0.01, "player must not move");

        JsonObject looked = Calls.call(ctx, "camera", String.format(java.util.Locale.ROOT,
                "{\"action\":\"look_at\",\"target\":{\"x\":%f,\"y\":%f,\"z\":%f}}", eye.x, eye.y, eye.z)).getAsJsonObject();
        check(Math.abs(looked.get("pitch").getAsFloat() - 90f) < 0.5, "looking straight down: " + looked);

        Calls.call(ctx, "camera", "{\"action\":\"reset\"}");
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position()).distanceTo(eye) < 0.5, "camera back at the eyes");

        // A per-capture camera does not leak into later frames.
        Calls.call(ctx, "screenshot", String.format(java.util.Locale.ROOT,
                "{\"maxSize\":256,\"camera\":{\"x\":%f,\"y\":%f,\"z\":%f,\"yaw\":90,\"pitch\":20,\"fov\":50}}", eye.x, eye.y + 20, eye.z));
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position()).distanceTo(eye) < 0.5, "per-capture camera cleared");
        check(ctx.computeOnClient(mc -> mc.options.fov().get()) != 50, "per-capture fov restored");

        // chat:false keeps chat lines out of a HUD shot: no white chat text left in the bottom-left.
        ctx.runOnClient(mc -> {
            for (int i = 0; i < 10; i++) mc.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("Teleported Steve to 1, 2, 3 #" + i));
        });
        ctx.waitTicks(2);
        JsonObject withChat = Calls.call(ctx, "screenshot", "{\"hud\":true,\"maxSize\":512,\"savePath\":\"" + dir.resolve("chat-on.png").toString().replace("\\", "\\\\") + "\"}").getAsJsonObject();
        JsonObject noChat = Calls.call(ctx, "screenshot", "{\"hud\":true,\"chat\":false,\"maxSize\":512,\"savePath\":\"" + dir.resolve("chat-off.png").toString().replace("\\", "\\\\") + "\"}").getAsJsonObject();
        double on = chatTextShare(withChat);
        double off = chatTextShare(noChat);
        check(on > 0.03, "the chat lines should show without chat:false: " + on);
        check(off < 0.002, "chat:false must hide the chat lines: " + off);
        check(!com.uxplima.craftwire.fabric.CaptureOptions.hideChat, "chat hiding is undone after the capture");
    }

    /** Share of near-white (text) pixels in the bottom-left area where chat lines sit. */
    private static double chatTextShare(JsonObject shot) throws java.io.IOException {
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(Path.of(shot.get("savedPath").getAsString()).toFile());
        int w = img.getWidth();
        int h = img.getHeight();
        int text = 0;
        int n = 0;
        for (int y = h * 46 / 100; y < h * 82 / 100; y++) {
            for (int x = 0; x < w * 35 / 100; x++) {
                int rgb = img.getRGB(x, y);
                if (((rgb >> 16) & 0xff) > 200 && ((rgb >> 8) & 0xff) > 200 && (rgb & 0xff) > 200) text++;
                n++;
            }
        }
        return (double) text / n;
    }
}
