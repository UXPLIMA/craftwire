package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.camera.CameraMotion;
import com.uxplima.craftwire.fabric.camera.CameraMotionJson;
import com.uxplima.craftwire.fabric.video.FfmpegArgs;
import com.uxplima.craftwire.fabric.video.FfmpegLocator;
import com.uxplima.craftwire.fabric.video.RecordParams;
import com.uxplima.craftwire.fabric.video.Recorder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/** record: start / stop / status of a video recording (video.Recorder). */
final class RecordHandler {
    private RecordHandler() {}

    private static volatile Path ffmpeg;

    static CompletableFuture<JsonElement> handle(JsonObject p, ClientScheduler s) {
        String action = Params.optString(p, "action").orElseThrow(() -> Params.invalid("action is required (start, stop or status)"));
        return switch (action) {
            case "status" -> s.call(() -> (JsonElement) Recorder.INSTANCE.status());
            case "stop" -> s.call(Recorder.INSTANCE::stop).thenCompose(f -> f).thenApply(o -> (JsonElement) o);
            case "start" -> start(p, s);
            default -> throw Params.invalid("unknown action: " + action);
        };
    }

    private static CompletableFuture<JsonElement> start(JsonObject p, ClientScheduler s) {
        JsonObject camera = p.has("camera") && p.get("camera").isJsonObject() ? p.getAsJsonObject("camera") : null;
        // Everything is validated before ffmpeg is looked for or the game is touched.
        RecordParams checked = RecordParams.parse(p, camera == null ? null : CameraMotionJson.parse(camera, null));
        FfmpegArgs.Container container = FfmpegArgs.Container.of(checked.savePath());
        return CompletableFuture.supplyAsync(() -> withEncoder(ffmpeg(), container))
                .thenCompose(ff -> s.call(() -> {
                    CameraMotion motion = camera == null ? null : CameraMotionJson.parse(camera, CameraHandler.currentPose(Minecraft.getInstance()));
                    RecordParams params = RecordParams.parse(p, motion);
                    Recorder.INSTANCE.prepare(params, ff);
                    return params;
                }))
                .thenCompose(params -> settle(s, params)
                        .thenCompose(v -> s.call(Recorder.INSTANCE::begin))
                        .handle((started, err) -> {
                            if (err == null) return CompletableFuture.completedFuture(started);
                            return s.call(() -> {
                                Recorder.INSTANCE.abort();
                                return Boolean.TRUE;
                            }).<JsonObject>thenApply(x -> {
                                throw err instanceof CompletionException c ? c : new CompletionException(err);
                            });
                        })
                        .thenCompose(f -> f)
                        .thenCompose(started -> params.waitForEnd() ? s.call(Recorder.INSTANCE::done).thenCompose(f -> f) : CompletableFuture.completedFuture(started)))
                .thenApply(o -> (JsonElement) o);
    }

    private static CompletableFuture<Void> settle(ClientScheduler s, RecordParams params) {
        // A GUI layer has no terrain in it.
        if (!params.waitForTerrain() || params.layer() != null) return CompletableFuture.completedFuture(null);
        return s.delay(3)
                .thenCompose(v -> ScreenshotHandler.sectionsBuilt(s, System.currentTimeMillis() + 5000))
                .thenCompose(v -> s.delay(2));
    }

    private static final Map<Path, String> ENCODERS = new ConcurrentHashMap<>();

    /** ProRes and VP9 are missing from some ffmpeg builds: say so before any frame is captured. */
    private static Path withEncoder(Path ffmpeg, FfmpegArgs.Container container) {
        if (container == FfmpegArgs.Container.MP4) return ffmpeg;
        String list = ENCODERS.computeIfAbsent(ffmpeg, f -> {
            try {
                Process p = new ProcessBuilder(f.toString(), "-hide_banner", "-encoders").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor(30, TimeUnit.SECONDS);
                return out;
            } catch (IOException e) {
                return "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        });
        String enc = FfmpegArgs.requiredEncoder(container);
        if (!FfmpegArgs.listsEncoder(list, enc)) {
            throw new AgentError("FFMPEG_FAILED", "This ffmpeg (" + ffmpeg + ") has no " + enc + " encoder.",
                    (container == FfmpegArgs.Container.MOV ? "Record to .webm instead" : "Record to .mov instead") + ", or install a full ffmpeg build.");
        }
        return ffmpeg;
    }

    private static Path ffmpeg() {
        Path known = ffmpeg;
        if (known != null && Files.isRegularFile(known)) return known;
        String os = System.getProperty("os.name");
        Path found = FfmpegLocator.find(System.getenv(), os, FfmpegLocator::runs).orElseThrow(() -> FfmpegLocator.notFound(os, System.getenv()));
        ffmpeg = found;
        return found;
    }
}
