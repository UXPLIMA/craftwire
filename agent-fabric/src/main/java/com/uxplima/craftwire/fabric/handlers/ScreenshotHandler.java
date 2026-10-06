package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.CaptureFaults;
import com.uxplima.craftwire.fabric.CaptureOptions;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.CraftwireAgent;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.camera.CameraOverride;
import com.uxplima.craftwire.fabric.mixin.HudAccessor;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

final class ScreenshotHandler {
    private ScreenshotHandler() {}

    private record Saved(boolean hudHidden, CameraOverride.Pose pose, int fov, boolean chatHidden) {
        static Saved of(Minecraft mc) {
            return new Saved(((HudAccessor) mc.gui.hud).craftwire$isHidden(), CameraOverride.INSTANCE.get(), mc.options.fov().get(),
                    CaptureOptions.hideChat);
        }

        void restore(Minecraft mc) {
            ((HudAccessor) mc.gui.hud).craftwire$setHidden(hudHidden);
            if (pose == null) CameraOverride.INSTANCE.clear();
            else CameraOverride.INSTANCE.set(pose);
            if (mc.options.fov().get() != fov) mc.options.fov().set(fov);
            CaptureOptions.hideChat = chatHidden;
        }
    }

    static CompletableFuture<JsonElement> capture(JsonObject p, CraftwireAgent agent) {
        boolean hud = Params.optBool(p, "hud").orElse(true);
        boolean chat = Params.optBool(p, "chat").orElse(true);
        int maxSize = Params.optInt(p, "maxSize").orElse(1600);
        String savePath = Params.optString(p, "savePath").orElse(null);
        String format = Params.optString(p, "format").orElse("auto");
        JsonObject camera = p.has("camera") && p.get("camera").isJsonObject() ? p.getAsJsonObject("camera") : null;
        ClientScheduler s = agent.scheduler();

        // Validate everything before touching game state, so a bad request never leaves the HUD hidden.
        CameraOverride.Pose pose = null;
        Integer fov = null;
        if (camera != null) {
            for (String k : new String[] {"x", "y", "z", "yaw", "pitch"}) {
                if (!camera.has(k)) throw Params.invalid("camera." + k + " is required");
            }
            pose = new CameraOverride.Pose(camera.get("x").getAsDouble(), camera.get("y").getAsDouble(), camera.get("z").getAsDouble(),
                    camera.get("yaw").getAsFloat(), camera.get("pitch").getAsFloat());
            fov = camera.has("fov") ? camera.get("fov").getAsInt() : null;
        }
        final CameraOverride.Pose capturePose = pose;
        final Integer captureFov = fov;

        CompletableFuture<Saved> prepared = s.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            Saved saved = Saved.of(mc);
            agent.setCaptureInProgress(true);
            if (!hud) ((HudAccessor) mc.gui.hud).craftwire$setHidden(true);
            if (!chat) CaptureOptions.hideChat = true;
            if (capturePose != null) CameraOverride.INSTANCE.set(capturePose);
            if (captureFov != null) mc.options.fov().set(captureFov);
            return saved;
        });

        return prepared.thenCompose(saved -> s.delay(3)
                        .thenCompose(v -> sectionsBuilt(s, System.currentTimeMillis() + SETTLE_MILLIS))
                        .thenCompose(v -> s.delay(2))   // the frame after the last section was built draws it
                        .thenCompose(v -> grab(s))
                        // Always restore (success or failure) and only then complete, so callers observe restored state.
                        .handle((file, err) -> s.call(() -> {
                            saved.restore(Minecraft.getInstance());
                            agent.setCaptureInProgress(false);
                            return Boolean.TRUE;
                        }).thenApply(done -> {
                            if (err != null) throw new CompletionException(err instanceof CompletionException && err.getCause() != null ? err.getCause() : err);
                            return file;
                        }))
                        .thenCompose(f -> f))
                .thenApplyAsync(file -> encode(file, maxSize, savePath, format));
    }

    /** Longest wait for terrain to finish building before a capture. */
    private static final long SETTLE_MILLIS = 5000;

    /**
     * Waits until every visible section is built (vanilla and Sodium both answer hasRenderedAllSections), so a shot
     * right after a camera move, or on a slow renderer, does not show missing terrain. Gives up after the deadline.
     */
    private static CompletableFuture<Void> sectionsBuilt(ClientScheduler s, long deadline) {
        return s.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            return mc.level == null || mc.levelRenderer.hasRenderedAllSections();
        }).thenCompose(done -> done || System.currentTimeMillis() > deadline
                ? CompletableFuture.<Void>completedFuture(null)
                : s.delay(1).thenCompose(v -> sectionsBuilt(s, deadline)));
    }

    private static CompletableFuture<Path> grab(ClientScheduler s) {
        CompletableFuture<Path> out = new CompletableFuture<>();
        long timeout = CaptureFaults.grabTimeoutMillis;
        if (CaptureFaults.stallNextGrab) {
            CaptureFaults.stallNextGrab = false;
            return withTimeout(out, timeout);
        }
        s.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                try {
                    Path tmp = Files.createTempFile("craftwire-", ".png");
                    image.writeToFile(tmp);
                    out.complete(tmp);
                } catch (IOException | RuntimeException e) {
                    out.completeExceptionally(e);
                } finally {
                    image.close();
                }
            });
            return Boolean.TRUE;
        }).exceptionally(e -> {
            out.completeExceptionally(e);
            return Boolean.FALSE;
        });
        return withTimeout(out, timeout);
    }

    // The GPU readback may never call back (e.g. a minimised window); without a deadline the HUD would stay hidden.
    private static CompletableFuture<Path> withTimeout(CompletableFuture<Path> grab, long timeoutMillis) {
        return grab.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).exceptionally(e -> {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                throw new AgentError("SCREENSHOT_FAILED", "The frame was not captured within " + timeoutMillis + " ms.",
                        "Make sure the Minecraft window is not minimised, then retry.");
            }
            throw cause instanceof RuntimeException r ? r : new CompletionException(cause);
        });
    }

    private static JsonElement encode(Path file, int maxSize, String savePath, String format) {
        try {
            BufferedImage full = ImageIO.read(file.toFile());
            String saved = null;
            try {
                if (savePath != null) {
                    Path target = Path.of(savePath).toAbsolutePath();
                    try {
                        if (target.getParent() != null) Files.createDirectories(target.getParent());
                        String name = target.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                            // Full-resolution JPEG: a 4K PNG is ~17 MB, the JPEG a tenth of that.
                            BufferedImage rgb = new BufferedImage(full.getWidth(), full.getHeight(), BufferedImage.TYPE_INT_RGB);
                            Graphics2D g = rgb.createGraphics();
                            g.drawImage(full, 0, 0, null);
                            g.dispose();
                            Files.write(target, jpeg(rgb, 0.92f));
                        } else {
                            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new AgentError("SAVE_FAILED", "Could not write " + target + ": " + e.getMessage(),
                                "Pass a writable savePath; missing directories are created automatically.");
                    }
                    saved = target.toString();
                }
            } finally {
                Files.deleteIfExists(file);
            }
            double scale = Math.min(1.0, maxSize / (double) Math.max(full.getWidth(), full.getHeight()));
            int w = Math.max(1, (int) Math.round(full.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(full.getHeight() * scale));
            BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(full, 0, 0, w, h, null);
            g.dispose();

            byte[] bytes = png(small);
            String mime = "image/png";
            if ("jpeg".equals(format) || ("auto".equals(format) && bytes.length > 1_500_000)) {
                bytes = jpeg(small, 0.9f);
                mime = "image/jpeg";
            }
            JsonObject o = new JsonObject();
            o.addProperty("mime", mime);
            o.addProperty("data", Base64.getEncoder().encodeToString(bytes));
            o.addProperty("width", w);
            o.addProperty("height", h);
            o.addProperty("fullWidth", full.getWidth());
            o.addProperty("fullHeight", full.getHeight());
            if (saved != null) o.addProperty("savedPath", saved);
            return o;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "png", buf);
        return buf.toByteArray();
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(buf)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return buf.toByteArray();
    }
}
