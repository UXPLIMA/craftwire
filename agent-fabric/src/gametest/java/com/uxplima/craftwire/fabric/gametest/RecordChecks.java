package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.CaptureOptions;
import com.uxplima.craftwire.fabric.camera.CameraOverride;
import com.uxplima.craftwire.fabric.video.FfmpegLocator;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** record and camera motions on the real game, checked with the real ffmpeg / ffprobe. */
final class RecordChecks {
    private RecordChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) throws IOException, InterruptedException {
        Path ffmpeg = FfmpegLocator.find(System.getenv(), System.getProperty("os.name"), FfmpegLocator::runs)
                .orElseThrow(() -> new AssertionError("the record game tests need ffmpeg (CI installs it)"));
        Path ffprobe = ffmpeg.resolveSibling(ffmpeg.getFileName().toString().replace("ffmpeg", "ffprobe"));
        Path dir = Files.createTempDirectory("craftwire-video");

        // Nothing recorded yet: stop and status say so.
        check("NOT_RECORDING".equals(Calls.error(ctx, "record", "{\"action\":\"stop\"}").code()), "stop without a recording");
        check(!Calls.call(ctx, "record", "{\"action\":\"status\"}").getAsJsonObject().get("recording").getAsBoolean(), "status idle");
        check("INVALID_PARAMS".equals(Calls.error(ctx, "record", "{\"action\":\"start\",\"savePath\":\"a.avi\"}").code()), ".avi refused");

        // 1. Two seconds with sound, waiting for the result. The game tests run muted; the recording must not be silent.
        double masterBefore = ctx.computeOnClient(mc -> mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).get());
        Path clip = dir.resolve("clip.mp4");
        CompletableFuture<JsonElement> f = Calls.start(ctx, "record", "{\"action\":\"start\",\"savePath\":\"" + json(clip)
                + "\",\"preset\":\"balanced\",\"durationMs\":2000,\"wait\":true}");
        for (int i = 0; i < 400 && !f.isDone(); i++) {
            if (i % 5 == 0) ctx.runOnClient(mc -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f)));
            ctx.waitTicks(1);
            Thread.sleep(10);
        }
        JsonObject r = result(ctx, f);
        check("duration".equals(r.get("reason").getAsString()), "ended by its duration: " + r);
        check(Math.abs(r.get("durationMs").getAsLong() - 2000) <= 20, "about 2000 ms: " + r);
        check(r.get("frames").getAsLong() == 60, "2 s at 30 fps = 60 frames: " + r);
        check(r.get("audio").getAsBoolean(), "with sound: " + r);
        JsonObject probe = probe(ffprobe, clip);
        JsonObject video = stream(probe, "video");
        check("h264".equals(video.get("codec_name").getAsString()), "H.264: " + video);
        check(video.get("width").getAsInt() == r.get("width").getAsInt() && video.get("height").getAsInt() == r.get("height").getAsInt(), "size: " + video);
        check(Math.abs(probe.getAsJsonObject("format").get("duration").getAsDouble() - 2.0) < 0.15, "file lasts ~2 s: " + probe.getAsJsonObject("format"));
        check("30/1".equals(video.get("r_frame_rate").getAsString()), "30 fps: " + video);
        check("aac".equals(stream(probe, "audio").get("codec_name").getAsString()), "AAC sound");
        double loudest = maxVolume(ffmpeg, clip);
        check(loudest > -40, "the pling sounds must be in the video, max_volume " + loudest + " dB");
        check(ctx.computeOnClient(mc -> mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).get()) == masterBefore, "master volume restored");
        check(!Calls.call(ctx, "hud.read", "{}").getAsJsonObject().get("hidden").getAsBoolean(), "HUD restored");
        try (var leftovers = Files.list(dir)) {
            check(leftovers.allMatch(p -> p.equals(clip)), "no temp files left next to the video");
        }

        // 2. The frames are the game's frame the right way up: a video frame matches a screenshot, not its mirror image.
        Path still = dir.resolve("still.png");
        Calls.call(ctx, "screenshot", "{\"hud\":false,\"maxSize\":512,\"savePath\":\"" + json(still) + "\"}");
        Path frame = dir.resolve("frame.png");
        run(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y", "-ss", "1.5", "-i", clip.toString(), "-frames:v", "1", frame.toString());
        BufferedImage shot = ImageIO.read(still.toFile());
        BufferedImage vid = ImageIO.read(frame.toFile());
        double same = difference(shot, vid, false), mirrored = difference(shot, vid, true);
        check(same < mirrored, "video frame upright (diff " + same + ") vs upside down (" + mirrored + ")");
        check(same < 40, "video frame looks like the screenshot, diff " + same);

        // 3. Stop by hand, no sound, light preset, a second recording refused while one runs.
        Path light = dir.resolve("light.mp4");
        JsonObject started = Calls.call(ctx, "record", "{\"action\":\"start\",\"savePath\":\"" + json(light) + "\",\"preset\":\"light\",\"audio\":false}").getAsJsonObject();
        check(started.get("recording").getAsBoolean() && started.get("height").getAsInt() <= 720, "started: " + started);
        check("ALREADY_RECORDING".equals(Calls.error(ctx, "record", "{\"action\":\"start\",\"savePath\":\"" + json(dir.resolve("b.mp4")) + "\"}").code()), "one at a time");
        waitMs(ctx, 1200);
        JsonObject status = Calls.call(ctx, "record", "{\"action\":\"status\"}").getAsJsonObject();
        check(status.get("recording").getAsBoolean() && status.get("frames").getAsLong() > 10, "status while recording: " + status);
        JsonObject stopped = result(ctx, Calls.start(ctx, "record", "{\"action\":\"stop\"}"));
        check("requested".equals(stopped.get("reason").getAsString()) && !stopped.get("audio").getAsBoolean(), "stopped: " + stopped);
        JsonObject lightProbe = probe(ffprobe, light);
        check(stream(lightProbe, "audio") == null, "no sound stream with audio:false");
        check(Files.size(light) < Files.size(clip), "light is smaller than balanced with sound");
        check(Calls.call(ctx, "record", "{\"action\":\"status\"}").getAsJsonObject().has("last"), "status keeps the last result");
        check(result(ctx, Calls.start(ctx, "record", "{\"action\":\"stop\"}")).get("savedPath").getAsString().equals(light.toString()), "stop again returns the last video");

        // 4. Camera orbit from the camera tool: moves on its own, always facing the center.
        Vec3 eye = ctx.computeOnClient(mc -> mc.player.getEyePosition());
        String center = String.format(Locale.ROOT, "{\"x\":%f,\"y\":%f,\"z\":%f}", eye.x, eye.y - 2, eye.z);
        JsonObject orbit = Calls.call(ctx, "camera", "{\"action\":\"orbit\",\"center\":" + center + ",\"radius\":12,\"durationMs\":4000}").getAsJsonObject();
        check("orbit".equals(orbit.getAsJsonObject("motion").get("kind").getAsString()), "orbit reported: " + orbit);
        Vec3 a = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position());
        waitMs(ctx, 500);
        Vec3 b = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position());
        check(a.distanceTo(b) > 1, "the camera moved by itself: " + a + " -> " + b);
        check(Math.abs(Math.hypot(b.x - eye.x, b.z - eye.z) - 12) < 0.05, "on the circle: " + b);
        check(ctx.computeOnClient(mc -> mc.player.getEyePosition()).distanceTo(eye) < 0.01, "the player stays");
        Calls.call(ctx, "camera", "{\"action\":\"reset\"}");

        // 5. record with a camera path: the length comes from the path, the camera is given back afterwards.
        Path flight = dir.resolve("flight.mp4");
        String path = String.format(Locale.ROOT, "{\"action\":\"path\",\"lookAt\":%s,\"keyframes\":[{\"t\":0,\"x\":%f,\"y\":%f,\"z\":%f},{\"t\":1500,\"x\":%f,\"y\":%f,\"z\":%f}]}",
                center, eye.x + 10, eye.y + 5, eye.z, eye.x, eye.y + 5, eye.z + 10);
        CompletableFuture<JsonElement> fl = Calls.start(ctx, "record", "{\"action\":\"start\",\"savePath\":\"" + json(flight) + "\",\"preset\":\"light\",\"audio\":false,\"wait\":true,\"camera\":" + path + "}");
        JsonObject flown = result(ctx, fl);
        check("duration".equals(flown.get("reason").getAsString()) && Math.abs(flown.get("durationMs").getAsLong() - 1500) <= 20, "path length: " + flown);
        check(ctx.computeOnClient(mc -> CameraOverride.INSTANCE.get()) == null, "camera given back to the player");
        check(!CaptureOptions.hideChat, "chat hiding undone");

        // 6. h265 plays where it is built in (tagged hvc1); without libx265 it fails with ffmpeg's reason.
        if (encoders(ffmpeg).contains("libx265")) {
            Path hevc = dir.resolve("hevc.mp4");
            JsonObject h = result(ctx, Calls.start(ctx, "record", "{\"action\":\"start\",\"savePath\":\"" + json(hevc) + "\",\"codec\":\"h265\",\"preset\":\"light\",\"audio\":false,\"durationMs\":800,\"wait\":true}"));
            check("h265".equals(h.get("codec").getAsString()), "h265 result: " + h);
            check("hevc".equals(stream(probe(ffprobe, hevc), "video").get("codec_name").getAsString()), "an HEVC stream");
        } else {
            AgentError noX265 = errorOf(ctx, Calls.start(ctx, "record", "{\"action\":\"start\",\"savePath\":\"" + json(dir.resolve("x.mp4")) + "\",\"codec\":\"h265\",\"audio\":false,\"durationMs\":500,\"wait\":true}"));
            check("RECORD_FAILED".equals(noX265.code()), "h265 without libx265: " + noX265.code());
        }
        check(!Calls.call(ctx, "record", "{\"action\":\"status\"}").getAsJsonObject().get("recording").getAsBoolean(), "idle at the end");
    }

    private static String json(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    private static void waitMs(ClientGameTestContext ctx, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            ctx.waitTicks(1);
            Thread.sleep(10);
        }
    }

    private static JsonObject result(ClientGameTestContext ctx, CompletableFuture<JsonElement> f) {
        ctx.waitFor(mc -> f.isDone(), 20_000);
        try {
            return f.join().getAsJsonObject();
        } catch (CompletionException e) {
            throw new AssertionError("record failed: " + e.getCause(), e.getCause());
        }
    }

    private static AgentError errorOf(ClientGameTestContext ctx, CompletableFuture<JsonElement> f) {
        ctx.waitFor(mc -> f.isDone(), 20_000);
        try {
            f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof AgentError a) return a;
            throw new AssertionError("non-AgentError " + e.getCause(), e.getCause());
        }
        throw new AssertionError("unexpectedly succeeded");
    }

    private static JsonObject probe(Path ffprobe, Path file) throws IOException, InterruptedException {
        String out = run(ffprobe.toString(), "-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height,r_frame_rate:format=duration",
                "-of", "json", file.toString());
        return JsonParser.parseString(out).getAsJsonObject();
    }

    private static JsonObject stream(JsonObject probe, String type) {
        JsonArray streams = probe.getAsJsonArray("streams");
        for (JsonElement s : streams) {
            if (type.equals(s.getAsJsonObject().get("codec_type").getAsString())) return s.getAsJsonObject();
        }
        return null;
    }

    private static double maxVolume(Path ffmpeg, Path file) throws IOException, InterruptedException {
        String out = run(ffmpeg.toString(), "-hide_banner", "-i", file.toString(), "-vn", "-af", "volumedetect", "-f", "null", "-");
        Matcher m = Pattern.compile("max_volume: (-?[0-9.]+|-inf) dB").matcher(out);
        check(m.find(), "volumedetect output: " + out);
        return m.group(1).equals("-inf") ? Double.NEGATIVE_INFINITY : Double.parseDouble(m.group(1));
    }

    private static String encoders(Path ffmpeg) throws IOException, InterruptedException {
        return run(ffmpeg.toString(), "-hide_banner", "-encoders");
    }

    private static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(List.of(cmd)).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(p.waitFor(60, TimeUnit.SECONDS), "timed out: " + List.of(cmd));
        check(p.exitValue() == 0, "failed (" + p.exitValue() + "): " + List.of(cmd) + "\n" + out);
        return out;
    }

    /** Mean RGB difference on a coarse 16×9 grid, optionally with {@code b} upside down. */
    private static double difference(BufferedImage a, BufferedImage b, boolean flipB) {
        List<Double> diffs = new ArrayList<>();
        for (int gy = 0; gy < 9; gy++) {
            for (int gx = 0; gx < 16; gx++) {
                int ax = (int) ((gx + 0.5) * a.getWidth() / 16), ay = (int) ((gy + 0.5) * a.getHeight() / 9);
                int bx = (int) ((gx + 0.5) * b.getWidth() / 16);
                int by = (int) (((flipB ? 8 - gy : gy) + 0.5) * b.getHeight() / 9);
                int p = a.getRGB(ax, ay), q = b.getRGB(bx, by);
                diffs.add((Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs((p & 255) - (q & 255))) / 3.0);
            }
        }
        return diffs.stream().mapToDouble(Double::doubleValue).average().orElse(255);
    }
}
