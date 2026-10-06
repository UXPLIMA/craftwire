package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.phys.Vec3;

/**
 * The camera override must render what is in front of the override camera, not what the player sees: terrain culling
 * has to follow the override. Checked on real frames: a lime wall behind the player's back, far away, has to fill the
 * middle of the picture. Run with Sodium too (agent-fabric/build.gradle, extraClientMods), which culls separately.
 */
final class CameraRenderChecks {
    private CameraRenderChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) throws IOException {
        Path dir = Files.createDirectories(FabricLoader.getInstance().getGameDir().resolve("craftwire-camera-shots"));
        String mods = FabricLoader.getInstance().isModLoaded("sodium") ? "sodium" : "vanilla";
        Vec3 feet = ctx.computeOnClient(mc -> mc.player.position());
        int px = (int) Math.floor(feet.x);
        int py = (int) Math.floor(feet.y);
        int pz = (int) Math.floor(feet.z);
        int wallZ = pz - 48;
        sp.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:lime_concrete",
                px - 8, py, wallZ, px + 8, py + 16, wallZ));
        ctx.runOnClient(mc -> mc.player.setYRot(0f)); // the player faces south, away from the wall
        sp.getConnection().waitForChunksRender();
        double camX = px + 0.5;
        double camY = py + 8.5;
        double camZ = wallZ + 10.5;

        // A per-capture camera.
        JsonObject shot = Calls.call(ctx, "screenshot", String.format(Locale.ROOT,
                "{\"hud\":false,\"maxSize\":512,\"savePath\":\"%s\",\"camera\":{\"x\":%f,\"y\":%f,\"z\":%f,\"yaw\":180,\"pitch\":0}}",
                escaped(dir.resolve(mods + "-capture.png")), camX, camY, camZ)).getAsJsonObject();
        double lime = limeShare(shot);
        check(lime > 0.6, mods + ": per-capture camera must show the wall in front of it, lime share " + lime + " in " + shot.get("savedPath"));

        // A camera set with the camera tool, as for a series of shots.
        Calls.call(ctx, "camera", String.format(Locale.ROOT,
                "{\"action\":\"set\",\"x\":%f,\"y\":%f,\"z\":%f,\"yaw\":180,\"pitch\":0}", camX, camY, camZ));
        shot = Calls.call(ctx, "screenshot", String.format(Locale.ROOT,
                "{\"hud\":false,\"maxSize\":512,\"savePath\":\"%s\"}", escaped(dir.resolve(mods + "-set.png")))).getAsJsonObject();
        Calls.call(ctx, "camera", "{\"action\":\"reset\"}");
        lime = limeShare(shot);
        check(lime > 0.6, mods + ": camera set must show the wall in front of it, lime share " + lime + " in " + shot.get("savedPath"));

        sp.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                px - 8, py, wallZ, px + 8, py + 16, wallZ));
    }

    private static String escaped(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    /** Share of pixels in the central third that look like lime concrete (clearly green). */
    private static double limeShare(JsonObject shot) throws IOException {
        BufferedImage img = ImageIO.read(Path.of(shot.get("savedPath").getAsString()).toFile());
        int w = img.getWidth();
        int h = img.getHeight();
        int lime = 0;
        int total = 0;
        for (int y = h / 3; y < 2 * h / 3; y++) {
            for (int x = w / 3; x < 2 * w / 3; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                if (g > r + 40 && g > b + 60) lime++;
                total++;
            }
        }
        return (double) lime / total;
    }
}
