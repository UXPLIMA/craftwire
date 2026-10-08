package com.uxplima.craftwire.fabric.video;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.CaptureOptions;
import com.uxplima.craftwire.fabric.CraftwireAgent;
import com.uxplima.craftwire.fabric.camera.CameraOverride;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import com.uxplima.craftwire.fabric.mixin.HudAccessor;
import com.uxplima.craftwire.fabric.mixin.SoundEngineAccessor;
import com.uxplima.craftwire.fabric.mixin.SoundManagerAccessor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;

/**
 * The `record` tool: real-time video of the game frame (and its sound) encoded by the user's ffmpeg. All methods
 * except the finishing thread run on the render thread.
 */
public final class Recorder {
    public static final Recorder INSTANCE = new Recorder();

    /** How long a stop waits for frames still being read back from the GPU. */
    private static final long READBACK_GRACE_NANOS = 1_000_000_000L;
    private static final long QUEUE_BYTES = 256L << 20;

    private enum State { PREPARING, RECORDING, FINISHING }

    /** What the recording changed in the game, put back when it ends. */
    private record Saved(boolean hudHidden, boolean chatHidden, boolean cameraOverridden, CameraOverride.Pose pose, Double masterVolume) {}

    private final class Session {
        final RecordParams params;
        final Path ffmpeg;
        final CompletableFuture<JsonObject> done = new CompletableFuture<>();
        State state = State.PREPARING;
        Saved saved;
        boolean audio;
        boolean soundLost;   // the WAV could not be written: the video is kept without sound
        int width, height;
        int[] output;
        long t0, stopNanos, finishDeadline;
        String reason;
        FramePacer pacer;
        Process process;
        FrameWriter writer;
        AudioTap tap;
        Path videoTemp, wavTemp;
        int pendingRepeats;
        long duplicated;
        final Deque<String> stderr = new ArrayDeque<>();

        Session(RecordParams params, Path ffmpeg) {
            this.params = params;
            this.ffmpeg = ffmpeg;
        }
    }

    private Session session;
    private JsonObject last;
    private AgentError lastError;

    private Recorder() {}

    public boolean isActive() {
        return session != null && session.state != State.PREPARING;
    }

    /** First step of record start: changes the game for the recording (HUD, camera, sound device). */
    public void prepare(RecordParams params, Path ffmpeg) {
        if (session != null) {
            throw new AgentError("ALREADY_RECORDING", session.state == State.FINISHING ? "The previous video is still being finished." : "A recording is already running.",
                    "Call record {action:'stop'} first, or record {action:'status'} to see it.");
        }
        Minecraft mc = Minecraft.getInstance();
        Session s = new Session(params, ffmpeg);
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        s.width = target.width;
        s.height = target.height;
        s.output = VideoSettings.outputSize(s.width, s.height, params.settings().maxHeight());
        OptionInstance<Double> master = mc.options.getSoundSourceOptionInstance(SoundSource.MASTER);
        boolean audio = params.settings().audio();
        s.saved = new Saved(((HudAccessor) mc.gui.hud).craftwire$isHidden(), CaptureOptions.hideChat, CameraOverride.INSTANCE.get() != null,
                CameraOverride.INSTANCE.get(), audio && master.get() == 0 ? master.get() : null);
        session = s;
        try {
            if (!params.hud()) ((HudAccessor) mc.gui.hud).craftwire$setHidden(true);
            if (!params.chat()) CaptureOptions.hideChat = true;
            if (params.motion() != null) CameraOverride.INSTANCE.set(params.motion().at(0));
            if (audio) {
                // A muted game (client_process clients, game tests) would record silence. The loopback device
                // plays nothing through the speakers, so full volume here is heard by nobody.
                if (s.saved.masterVolume() != null) master.set(1.0);
                openLoopback(mc);
                s.audio = true;
            }
        } catch (RuntimeException e) {
            restore(s);
            session = null;
            throw e;
        }
    }

    private static void openLoopback(Minecraft mc) {
        SoundEngine engine = ((SoundManagerAccessor) mc.getSoundManager()).craftwire$getSoundEngine();
        AudioTap.arm();
        engine.reload();
        if (AudioTap.device() == 0 || !((SoundEngineAccessor) engine).craftwire$isLoaded()) {
            AudioTap.disarm();
            engine.reload();
            throw new AgentError("AUDIO_UNAVAILABLE", "The game's sound could not be routed to the recording (no OpenAL loopback device).",
                    "Record without sound: record {action:'start', audio:false, ...}. The game log has the OpenAL error.");
        }
    }

    private static void closeLoopback(Minecraft mc) {
        AudioTap.disarm();
        ((SoundManagerAccessor) mc.getSoundManager()).craftwire$getSoundEngine().reload();
    }

    /** Called when preparing failed (e.g. the terrain wait broke): puts everything back. */
    public void abort() {
        Session s = session;
        if (s == null || s.state != State.PREPARING) return;
        restore(s);
        session = null;
    }

    /** Second step of record start: ffmpeg runs and frames flow from the next rendered frame on. */
    public JsonObject begin() {
        Session s = session;
        if (s == null || s.state != State.PREPARING) throw new AgentError("NOT_RECORDING", "The recording was cancelled before it started.", "Start it again.");
        RecordParams p = s.params;
        try {
            Path dir = p.savePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            String base = "." + p.savePath().getFileName() + "." + Long.toHexString(ThreadLocalRandom.current().nextLong());
            s.videoTemp = p.savePath().resolveSibling(base + ".video.mp4");
            s.wavTemp = s.audio ? p.savePath().resolveSibling(base + ".wav") : null;
            List<String> cmd = FfmpegArgs.video(s.ffmpeg.toString(), s.width, s.height, p.settings(), s.videoTemp);
            s.process = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Thread.ofPlatform().daemon().name("Craftwire ffmpeg log").start(() -> readStderr(s));
            int frameBytes = s.width * s.height * 4;
            s.writer = new FrameWriter(s.process.getOutputStream(), frameBytes, (int) Math.max(2, Math.min(64, QUEUE_BYTES / frameBytes)));
            s.pacer = new FramePacer(p.settings().fps());
            s.t0 = System.nanoTime();
            if (s.audio) {
                s.tap = new AudioTap();
                s.tap.start(s.wavTemp, s.t0);
            }
        } catch (IOException | RuntimeException e) {
            if (s.process != null) s.process.destroyForcibly();
            deleteTemps(s);
            restore(s);
            session = null;
            throw new AgentError("RECORD_FAILED", "Could not start recording: " + e.getMessage(), "Check that savePath is writable and ffmpeg works (ffmpeg -version).");
        }
        if (p.motion() != null) CameraOverride.INSTANCE.play(p.motion(), s.t0);
        s.state = State.RECORDING;
        JsonObject o = new JsonObject();
        o.addProperty("recording", true);
        o.addProperty("savePath", p.savePath().toString());
        o.addProperty("width", s.output[0]);
        o.addProperty("height", s.output[1]);
        o.addProperty("fps", p.settings().fps());
        o.addProperty("preset", p.settings().preset());
        o.addProperty("audio", s.audio);
        if (p.durationMs() != null) o.addProperty("durationMs", p.durationMs());
        o.addProperty("maxSeconds", p.maxSeconds());
        return o;
    }

    /** The finished video of the running (or just finished) recording. */
    public CompletableFuture<JsonObject> done() {
        Session s = session;
        if (s != null) return s.done;
        if (last != null) return CompletableFuture.completedFuture(last);
        return CompletableFuture.failedFuture(lastError != null ? lastError : notRecording());
    }

    /** After every rendered frame (compat FrameRenderedMixin). */
    public void onFrame() {
        Session s = session;
        if (s == null) return;
        long now = System.nanoTime();
        if (s.state == State.RECORDING) {
            long t = now - s.t0;
            RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            Long duration = s.params.durationMs();
            if (s.writer.failure() != null || !s.process.isAlive()) finish(s, "error", now);
            else if (duration != null && t >= duration * 1_000_000L) finish(s, "duration", s.t0 + duration * 1_000_000L);
            else if (t >= s.params.maxSeconds() * 1_000_000_000L) finish(s, "maxSeconds", s.t0 + s.params.maxSeconds() * 1_000_000_000L);
            else if (target.width != s.width || target.height != s.height) finish(s, "resized", now);
            else capture(s, target, s.pacer.framesFor(t));
        }
        if (s.state == State.FINISHING && s.process != null && (s.pendingRepeats == 0 || now > s.finishDeadline)) finalizeAsync(s);
    }

    private void capture(Session s, RenderTarget target, int repeats) {
        if (repeats == 0) return;
        s.duplicated += repeats - 1;
        s.pendingRepeats += repeats;
        try {
            ClientCompat.get().readPixels(target, pixels -> {
                s.pendingRepeats -= repeats;
                if (s.process != null) s.writer.submit(pixels, repeats);
            });
        } catch (RuntimeException e) {
            s.pendingRepeats -= repeats;
            s.writer.dropFrames(repeats);
            CraftwireAgent.LOGGER.warn("Craftwire could not read a video frame", e);
        }
    }

    /** record stop. */
    public CompletableFuture<JsonObject> stop() {
        Session s = session;
        if (s == null) return done();
        if (s.state == State.PREPARING) {
            throw new AgentError("RECORD_STARTING", "The recording is still starting (waiting for terrain).", "Retry the stop in a moment.");
        }
        if (s.state == State.RECORDING) finish(s, "requested", System.nanoTime());
        // nothing left on the GPU: finish now, without waiting for another rendered frame
        if (s.process != null && s.pendingRepeats == 0) finalizeAsync(s);
        return s.done;
    }

    public JsonObject status() {
        Session s = session;
        JsonObject o = new JsonObject();
        o.addProperty("recording", s != null);
        if (s != null) {
            o.addProperty("state", s.state.name().toLowerCase(java.util.Locale.ROOT));
            o.addProperty("savePath", s.params.savePath().toString());
            if (s.state != State.PREPARING) {
                o.addProperty("elapsedMs", ((s.state == State.FINISHING ? s.stopNanos : System.nanoTime()) - s.t0) / 1_000_000);
                o.addProperty("frames", s.pacer.emitted());
                o.addProperty("duplicatedFrames", s.duplicated);
                o.addProperty("droppedFrames", s.writer.dropped());
                o.addProperty("queuedFrames", s.writer.queued());
            }
        } else if (last != null) {
            o.add("last", last);
        } else if (lastError != null) {
            JsonObject e = new JsonObject();
            e.addProperty("code", lastError.code());
            e.addProperty("message", lastError.getMessage());
            o.add("lastError", e);
        }
        return o;
    }

    /** The game is closing: finish the file before it goes (at most 15 s). */
    public void shutdown() {
        Session s = session;
        if (s == null || s.state == State.PREPARING) return;
        if (s.state == State.RECORDING) finish(s, "quit", System.nanoTime());
        if (s.process != null) finalizeAsync(s);
        try {
            s.done.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            CraftwireAgent.LOGGER.warn("Craftwire could not finish the video before the game closed", e);
        }
    }

    private void finish(Session s, String reason, long stopNanos) {
        s.state = State.FINISHING;
        s.reason = reason;
        s.stopNanos = stopNanos;
        s.finishDeadline = System.nanoTime() + READBACK_GRACE_NANOS;
        restore(s);
    }

    /** Hands the rest to a thread: close the frame stream, wait for ffmpeg, add the sound, move into place. */
    private void finalizeAsync(Session s) {
        if (s.pendingRepeats > 0) s.writer.dropFrames(s.pendingRepeats);
        s.pendingRepeats = 0;
        int pad = s.pacer.padTo(s.stopNanos - s.t0);
        Process process = s.process;
        s.process = null;   // later readback callbacks are ignored
        ClientCompat.get().releaseReadBuffers();
        long finishing = System.nanoTime();
        Thread.ofPlatform().daemon().name("Craftwire video finish").start(() -> {
            try {
                JsonObject result = complete(s, process, pad, finishing);
                Minecraft.getInstance().execute(() -> {
                    if (session == s) session = null;
                    last = result;
                    lastError = null;
                });
                s.done.complete(result);
            } catch (AgentError e) {
                deleteTemps(s);
                Minecraft.getInstance().execute(() -> {
                    if (session == s) session = null;
                    last = null;
                    lastError = e;
                });
                s.done.completeExceptionally(e);
            }
        });
    }

    private JsonObject complete(Session s, Process process, int pad, long finishing) {
        RecordParams p = s.params;
        try {
            try {
                s.writer.finish(pad);
            } catch (IOException e) {
                // ffmpeg closed its input: its own error says why
            }
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw failed(s, "ffmpeg did not finish within 2 minutes");
            }
            if (process.exitValue() != 0) throw failed(s, "ffmpeg exited with code " + process.exitValue());
            if (s.writer.failure() != null) throw failed(s, "writing frames to ffmpeg failed: " + s.writer.failure().getMessage());
            Path result = s.videoTemp;
            if (s.wavTemp != null && !s.soundLost) {
                Path muxed = s.videoTemp.resolveSibling(s.videoTemp.getFileName() + ".mux.mp4");
                Process mux = new ProcessBuilder(FfmpegArgs.mux(s.ffmpeg.toString(), s.videoTemp, s.wavTemp, p.settings().audioKbps(), muxed))
                        .redirectErrorStream(true).start();
                String out = new String(mux.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!mux.waitFor(2, TimeUnit.MINUTES) || mux.exitValue() != 0) {
                    Files.deleteIfExists(muxed);
                    throw new AgentError("RECORD_FAILED", "Adding the sound to the video failed: " + out, "Retry with audio:false.");
                }
                Files.deleteIfExists(s.videoTemp);
                result = muxed;
            }
            move(result, p.savePath());
            deleteTemps(s);
            JsonObject o = new JsonObject();
            o.addProperty("savedPath", p.savePath().toString());
            o.addProperty("reason", s.reason);
            o.addProperty("durationMs", (s.stopNanos - s.t0) / 1_000_000);
            o.addProperty("fps", p.settings().fps());
            o.addProperty("width", s.output[0]);
            o.addProperty("height", s.output[1]);
            o.addProperty("frames", s.pacer.emitted());
            o.addProperty("duplicatedFrames", s.duplicated);
            o.addProperty("droppedFrames", s.writer.dropped());
            o.addProperty("audio", s.audio && !s.soundLost);
            o.addProperty("codec", p.settings().codec());
            o.addProperty("preset", p.settings().preset());
            o.addProperty("crf", p.settings().crf());
            o.addProperty("speed", p.settings().speed());
            o.addProperty("sizeBytes", Files.size(p.savePath()));
            o.addProperty("encodeMs", (System.nanoTime() - finishing) / 1_000_000);
            if (s.writer.dropped() > 0) {
                o.addProperty("note", "The encoder could not keep up, so some frames repeat the one before. Use a faster preset (balanced or light), a lower fps or resolution.");
            }
            return o;
        } catch (IOException e) {
            throw new AgentError("RECORD_FAILED", "Could not write the video: " + e.getMessage(), "Check free disk space and that savePath is writable.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentError("RECORD_FAILED", "Interrupted while finishing the video.", "Record it again.");
        }
    }

    private AgentError failed(Session s, String what) {
        String log;
        synchronized (s.stderr) {
            log = String.join("\n", s.stderr);
        }
        return new AgentError("RECORD_FAILED", what + (log.isEmpty() ? "." : ":\n" + log),
                "h265 needs an ffmpeg built with libx265; otherwise check the ffmpeg output above.");
    }

    private static void readStderr(Session s) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(s.process.getErrorStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                synchronized (s.stderr) {
                    s.stderr.addLast(line);
                    while (s.stderr.size() > 20) s.stderr.removeFirst();
                }
            }
        } catch (IOException | NullPointerException ignored) {
            // the process is gone
        }
    }

    /** Render thread: puts back what prepare() changed and stops the sound capture. */
    private void restore(Session s) {
        Minecraft mc = Minecraft.getInstance();
        Saved saved = s.saved;
        if (s.tap != null) {
            try {
                s.tap.stop(s.stopNanos != 0 ? s.stopNanos : System.nanoTime());
            } catch (IOException e) {
                CraftwireAgent.LOGGER.warn("Craftwire could not write the recorded sound", e);
                s.soundLost = true;   // the video is still worth keeping
            }
        }
        if (s.audio) closeLoopback(mc);
        if (saved == null) return;
        ((HudAccessor) mc.gui.hud).craftwire$setHidden(saved.hudHidden());
        CaptureOptions.hideChat = saved.chatHidden();
        if (s.params.motion() != null) {
            if (saved.cameraOverridden()) CameraOverride.INSTANCE.set(saved.pose());
            else CameraOverride.INSTANCE.clear();
        }
        if (saved.masterVolume() != null) mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(saved.masterVolume());
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTemps(Session s) {
        for (Path p : new Path[] {s.videoTemp, s.wavTemp, s.videoTemp == null ? null : s.videoTemp.resolveSibling(s.videoTemp.getFileName() + ".mux.mp4")}) {
            if (p == null) continue;
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // a leftover hidden temp file is not worth failing over
            }
        }
    }

    private static AgentError notRecording() {
        return new AgentError("NOT_RECORDING", "No recording is running.", "Start one with record {action:'start', savePath:'clip.mp4'}.");
    }
}
