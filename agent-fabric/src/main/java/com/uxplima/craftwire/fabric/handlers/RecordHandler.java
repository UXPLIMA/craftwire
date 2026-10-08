package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.camera.CameraMotion;
import com.uxplima.craftwire.fabric.camera.CameraMotionJson;
import com.uxplima.craftwire.fabric.video.FfmpegLocator;
import com.uxplima.craftwire.fabric.video.RecordParams;
import com.uxplima.craftwire.fabric.video.Recorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
        RecordParams.parse(p, camera == null ? null : CameraMotionJson.parse(camera, null));
        return CompletableFuture.supplyAsync(RecordHandler::ffmpeg)
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
        if (!params.waitForTerrain()) return CompletableFuture.completedFuture(null);
        return s.delay(3)
                .thenCompose(v -> ScreenshotHandler.sectionsBuilt(s, System.currentTimeMillis() + 5000))
                .thenCompose(v -> s.delay(2));
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
