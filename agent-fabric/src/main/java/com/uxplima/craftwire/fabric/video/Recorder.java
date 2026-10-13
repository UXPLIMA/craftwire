package com.uxplima.craftwire.fabric.video;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.CaptureOptions;
import com.uxplima.craftwire.fabric.CraftwireAgent;
import com.uxplima.craftwire.fabric.camera.CameraOverride;
import com.uxplima.craftwire.fabric.capture.GuiCapture;
import com.uxplima.craftwire.fabric.capture.GuiLayer;
import com.uxplima.craftwire.fabric.capture.GuiScale;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * The `record` tool: real-time video of the game frame (and its sound) encoded by the user's ffmpeg, or of the open
 * GUI on a transparent canvas (ProRes .mov / VP9 .webm). All methods except the finishing thread run on the render thread.
 */
public final class Recorder {
    public static final Recorder INSTANCE = new Recorder();

    /** How long a stop waits for frames still being read back from the GPU. */
    private static final long READBACK_GRACE_NANOS = 1_000_000_000L;
    private static final long QUEUE_BYTES = 256L << 20;

    private enum State { PREPARING, RECORDING, FINISHING }

    /** What the recording changed in the game, put back when it ends. */
    private record Saved(boolean hudHidden, boolean chatHidden, boolean cameraOverridden, CameraOverride.Pose pose, Double masterVolume, int guiScale) {}

    /** One output file: the world frame (part null) or a GUI layer drawn on canvas {@code index}. */
    private static final class Track {
        final GuiLayer part;
        final int index;
        final Path savePath;
        Path videoTemp;
        Process process;
        FrameWriter writer;
        int pendingRepeats;
        final Deque<String> stderr = new ArrayDeque<>();

        Track(GuiLayer part, int index, Path savePath) {
            this.part = part;
            this.index = index;
            this.savePath = savePath;
        }
    }

    private final class Session {
        final RecordParams params;
        final Path ffmpeg;
        final FfmpegArgs.Container container;
        final List<Track> tracks = new ArrayList<>();
        /** Completed on the render thread once the recorder is idle again: callers then see it idle. */
        final CompletableFuture<JsonObject> done = new CompletableFuture<>();
        /** Completed by the finishing thread when the files are in place (game shutdown waits on this). */
        final CompletableFuture<Void> written = new CompletableFuture<>();
        State state = State.PREPARING;
        Saved saved;
        boolean audio;
        boolean soundLost;   // the WAV could not be written: the video is kept without sound
        boolean noSoundFiles;   // a client_process client started without sounds:true has no .ogg files
        boolean live;   // ffmpeg is running and frames are accepted
        int width, height;
        int[] output;
        long t0, stopNanos, finishDeadline;
        String reason;
        FramePacer pacer;
        AudioTap tap;
        Path wavTemp;
        long duplicated;

        Session(RecordParams params, Path ffmpeg) {
            this.params = params;
            this.ffmpeg = ffmpeg;
            this.container = FfmpegArgs.Container.of(params.savePath());
            GuiLayer layer = params.layer();
            if (layer == null) tracks.add(new Track(null, 0, params.savePath()));
            else if (params.split()) {
                tracks.add(new Track(GuiLayer.GUI, 0, GuiCapture.splitPath(params.savePath(), "gui")));
                tracks.add(new Track(GuiLayer.TOOLTIP, 1, GuiCapture.splitPath(params.savePath(), "tooltip")));
            } else tracks.add(new Track(layer, 0, params.savePath()));
        }

        int pending() {
            int n = 0;
            for (Track t : tracks) n += t.pendingRepeats;
            return n;
        }
    }

    private static final String NO_SOUND_FILES = "This game has no sound files, so the video is silent: start the client with client_process {sounds:true}.";

    private Session session;
    private JsonObject last;
    private AgentError lastError;

    private Recorder() {}

    public boolean isActive() {
        return session != null && session.state != State.PREPARING;
    }

    /** First step of record start: changes the game for the recording (HUD, camera, GUI scale, sound device). */
    public void prepare(RecordParams params, Path ffmpeg) {
        if (session != null) {
            throw new AgentError("ALREADY_RECORDING", session.state == State.FINISHING ? "The previous video is still being finished." : "A recording is already running.",
                    "Call record {action:'stop'} first, or record {action:'status'} to see it.");
        }
        Minecraft mc = Minecraft.getInstance();
        GuiLayer layer = params.layer();
        if (layer != null) checkLayer(mc, layer);
        Session s = new Session(params, ffmpeg);
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        s.width = target.width;
        s.height = target.height;
        s.output = VideoSettings.outputSize(s.width, s.height, params.settings().maxHeight());
        OptionInstance<Double> master = mc.options.getSoundSourceOptionInstance(SoundSource.MASTER);
        boolean audio = params.settings().audio();
        s.saved = new Saved(((HudAccessor) mc.gui.hud).craftwire$isHidden(), CaptureOptions.hideChat, CameraOverride.INSTANCE.get() != null,
                CameraOverride.INSTANCE.get(), audio && master.get() == 0 ? master.get() : null, mc.options.guiScale().get());
        session = s;
        try {
            // A GUI layer is drawn without the HUD anyway; the player's own view stays as it is.
            if (layer == null && !params.hud()) ((HudAccessor) mc.gui.hud).craftwire$setHidden(true);
            if (layer == null && !params.chat()) CaptureOptions.hideChat = true;
            if (params.motion() != null) CameraOverride.INSTANCE.set(params.motion().at(0));
            if (params.guiScale() != null) GuiScale.apply(mc, params.guiScale());
            if (audio) {
                // A muted game (client_process clients, game tests) would record silence. The loopback device
                // plays nothing through the speakers, so full volume here is heard by nobody.
                if (s.saved.masterVolume() != null) master.set(1.0);
                openLoopback(mc);
                s.audio = true;
                s.noSoundFiles = mc.getResourceManager().getResource(Identifier.fromNamespaceAndPath("minecraft", "sounds/random/click.ogg")).isEmpty();
            }
        } catch (RuntimeException e) {
            restore(s);
            session = null;
            throw e;
        }
    }

    /** A GUI recording needs an open screen; a tooltip-only recording needs a tooltip at the start. */
    private static void checkLayer(Minecraft mc, GuiLayer layer) {
        if (layer.screen() && mc.gui.screen() == null) {
            throw new AgentError("NO_SCREEN", "No screen is open.", "Open the menu first (gui_action, a command or the plugin's item), then record.");
        }
        if (layer == GuiLayer.TOOLTIP) {
            RenderTarget main = mc.gameRenderer.mainRenderTarget();
            ClientCompat compat = ClientCompat.get();
            if (!compat.drawGui(GuiLayer.TOOLTIP, compat.guiCanvas(main.width, main.height, 0), 0).tooltip()) {
                throw new AgentError("NO_TOOLTIP", "No tooltip is shown.", "Hover an item first: gui_action {action:'hover', slot}.");
            }
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

    private static String extension(FfmpegArgs.Container c) {
        return c.name().toLowerCase(Locale.ROOT);
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
            s.wavTemp = s.audio ? p.savePath().resolveSibling(base + ".wav") : null;
            int frameBytes = s.width * s.height * 4;
            for (Track t : s.tracks) {
                String part = t.part == null ? "" : "." + t.index;
                t.videoTemp = p.savePath().resolveSibling(base + part + ".video." + extension(s.container));
                List<String> cmd = FfmpegArgs.video(s.ffmpeg.toString(), s.width, s.height, p.settings(), t.videoTemp, s.container);
                t.process = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                Thread.ofPlatform().daemon().name("Craftwire ffmpeg log").start(() -> readStderr(t));
                t.writer = new FrameWriter(t.process.getOutputStream(), frameBytes,
                        (int) Math.max(2, Math.min(64, QUEUE_BYTES / s.tracks.size() / frameBytes)));
            }
            s.live = true;
            s.pacer = new FramePacer(p.settings().fps());
            s.t0 = System.nanoTime();
            if (s.audio) {
                s.tap = new AudioTap();
                s.tap.start(s.wavTemp, s.t0);
            }
        } catch (IOException | RuntimeException e) {
            for (Track t : s.tracks) if (t.process != null) t.process.destroyForcibly();
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
        if (p.split()) o.add("savePaths", savePaths(s));
        o.addProperty("width", s.output[0]);
        o.addProperty("height", s.output[1]);
        o.addProperty("fps", p.settings().fps());
        o.addProperty("preset", p.settings().preset());
        o.addProperty("audio", s.audio);
        if (p.durationMs() != null) o.addProperty("durationMs", p.durationMs());
        o.addProperty("maxSeconds", p.maxSeconds());
        if (s.noSoundFiles) o.addProperty("note", NO_SOUND_FILES);
        return o;
    }

    private static JsonArray savePaths(Session s) {
        JsonArray a = new JsonArray();
        for (Track t : s.tracks) a.add(t.savePath.toString());
        return a;
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
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            Long duration = s.params.durationMs();
            if (failedTrack(s)) finish(s, "error", now);
            else if (duration != null && t >= duration * 1_000_000L) finish(s, "duration", s.t0 + duration * 1_000_000L);
            else if (t >= s.params.maxSeconds() * 1_000_000_000L) finish(s, "maxSeconds", s.t0 + s.params.maxSeconds() * 1_000_000_000L);
            else if (main.width != s.width || main.height != s.height) finish(s, "resized", now);
            else {
                int repeats = s.pacer.framesFor(t);
                if (repeats > 0) {
                    s.duplicated += repeats - 1;
                    for (Track track : s.tracks) capture(s, track, frameOf(track, main), repeats);
                }
            }
        }
        if (s.state == State.FINISHING && s.live && (s.pending() == 0 || now > s.finishDeadline)) finalizeAsync(s);
    }

    private static boolean failedTrack(Session s) {
        for (Track t : s.tracks) if (t.writer.failure() != null || !t.process.isAlive()) return true;
        return false;
    }

    /** The target to read for this track: the game frame, or its GUI layer drawn on a transparent canvas. */
    private static RenderTarget frameOf(Track track, RenderTarget main) {
        if (track.part == null) return main;
        ClientCompat compat = ClientCompat.get();
        RenderTarget canvas = compat.guiCanvas(main.width, main.height, track.index);
        // With no screen open (it was closed during the recording) the canvas is simply cleared: transparent frames.
        compat.drawGui(track.part, canvas, 0);
        return canvas;
    }

    private void capture(Session s, Track t, RenderTarget target, int repeats) {
        t.pendingRepeats += repeats;
        try {
            ClientCompat.get().readPixels(target, pixels -> {
                t.pendingRepeats -= repeats;
                if (s.live) t.writer.submit(pixels, repeats);
            });
        } catch (RuntimeException e) {
            t.pendingRepeats -= repeats;
            t.writer.dropFrames(repeats);
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
        if (s.live && s.pending() == 0) finalizeAsync(s);
        return s.done;
    }

    public JsonObject status() {
        Session s = session;
        JsonObject o = new JsonObject();
        o.addProperty("recording", s != null);
        if (s != null) {
            o.addProperty("state", s.state.name().toLowerCase(Locale.ROOT));
            o.addProperty("savePath", s.params.savePath().toString());
            if (s.state != State.PREPARING) {
                o.addProperty("elapsedMs", ((s.state == State.FINISHING ? s.stopNanos : System.nanoTime()) - s.t0) / 1_000_000);
                o.addProperty("frames", s.pacer.emitted());
                o.addProperty("duplicatedFrames", s.duplicated);
                o.addProperty("droppedFrames", dropped(s));
                long queued = 0;
                for (Track t : s.tracks) queued = Math.max(queued, t.writer.queued());
                o.addProperty("queuedFrames", queued);
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

    private static long dropped(Session s) {
        long n = 0;
        for (Track t : s.tracks) n = Math.max(n, t.writer.dropped());
        return n;
    }

    /** The game is closing: finish the file before it goes (at most 15 s). */
    public void shutdown() {
        Session s = session;
        if (s == null || s.state == State.PREPARING) return;
        if (s.state == State.RECORDING) finish(s, "quit", System.nanoTime());
        if (s.live) finalizeAsync(s);
        try {
            s.written.get(15, TimeUnit.SECONDS);
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

    /** Hands the rest to a thread: close the frame streams, wait for ffmpeg, add the sound, move into place. */
    private void finalizeAsync(Session s) {
        for (Track t : s.tracks) {
            if (t.pendingRepeats > 0) t.writer.dropFrames(t.pendingRepeats);
            t.pendingRepeats = 0;
        }
        int pad = s.pacer.padTo(s.stopNanos - s.t0);
        s.live = false;   // later readback callbacks are ignored
        ClientCompat.get().releaseReadBuffers();
        long finishing = System.nanoTime();
        Thread.ofPlatform().daemon().name("Craftwire video finish").start(() -> {
            try {
                JsonObject result = complete(s, pad, finishing);
                s.written.complete(null);
                Minecraft.getInstance().execute(() -> {
                    if (session == s) session = null;
                    last = result;
                    lastError = null;
                    s.done.complete(result);
                });
            } catch (AgentError e) {
                deleteTemps(s);
                s.written.complete(null);
                Minecraft.getInstance().execute(() -> {
                    if (session == s) session = null;
                    last = null;
                    lastError = e;
                    s.done.completeExceptionally(e);
                });
            }
        });
    }

    private JsonObject complete(Session s, int pad, long finishing) {
        RecordParams p = s.params;
        try {
            for (Track t : s.tracks) {
                try {
                    t.writer.finish(pad);
                } catch (IOException e) {
                    // ffmpeg closed its input: its own error says why
                }
            }
            for (Track t : s.tracks) {
                if (!t.process.waitFor(2, TimeUnit.MINUTES)) {
                    t.process.destroyForcibly();
                    throw failed(s, t, "ffmpeg did not finish within 2 minutes");
                }
                if (t.process.exitValue() != 0) throw failed(s, t, "ffmpeg exited with code " + t.process.exitValue());
                if (t.writer.failure() != null) throw failed(s, t, "writing frames to ffmpeg failed: " + t.writer.failure().getMessage());
            }
            for (Track t : s.tracks) {
                Path result = t.videoTemp;
                if (s.wavTemp != null && !s.soundLost) {
                    Path muxed = muxTemp(t, s.container);
                    Process mux = new ProcessBuilder(FfmpegArgs.mux(s.ffmpeg.toString(), t.videoTemp, s.wavTemp, p.settings().audioKbps(), muxed, s.container))
                            .redirectErrorStream(true).start();
                    String out = new String(mux.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (!mux.waitFor(2, TimeUnit.MINUTES) || mux.exitValue() != 0) {
                        Files.deleteIfExists(muxed);
                        throw new AgentError("RECORD_FAILED", "Adding the sound to the video failed: " + out, "Retry with audio:false.");
                    }
                    Files.deleteIfExists(t.videoTemp);
                    result = muxed;
                }
                move(result, t.savePath);
            }
            deleteTemps(s);
            JsonObject o = new JsonObject();
            o.addProperty("savedPath", s.tracks.get(0).savePath.toString());
            if (p.split()) o.add("savedPaths", savePaths(s));
            o.addProperty("reason", s.reason);
            o.addProperty("durationMs", (s.stopNanos - s.t0) / 1_000_000);
            o.addProperty("fps", p.settings().fps());
            o.addProperty("width", s.output[0]);
            o.addProperty("height", s.output[1]);
            o.addProperty("frames", s.pacer.emitted());
            o.addProperty("duplicatedFrames", s.duplicated);
            o.addProperty("droppedFrames", dropped(s));
            o.addProperty("audio", s.audio && !s.soundLost);
            if (p.layer() == null) {
                o.addProperty("codec", p.settings().codec());
                o.addProperty("preset", p.settings().preset());
                o.addProperty("crf", p.settings().crf());
                o.addProperty("speed", p.settings().speed());
            } else {
                o.addProperty("layer", p.layer() == GuiLayer.GUI ? "gui" : p.layer() == GuiLayer.TOOLTIP ? "tooltip" : "gui+tooltip");
                o.addProperty("codec", FfmpegArgs.requiredEncoder(s.container));
            }
            long size = 0;
            for (Track t : s.tracks) size += Files.size(t.savePath);
            o.addProperty("sizeBytes", size);
            o.addProperty("encodeMs", (System.nanoTime() - finishing) / 1_000_000);
            List<String> notes = new ArrayList<>();
            if (dropped(s) > 0) {
                notes.add("The encoder could not keep up, so some frames repeat the one before. Use a faster preset (balanced or light), a lower fps or resolution.");
            }
            if (s.noSoundFiles) notes.add(NO_SOUND_FILES);
            if (!notes.isEmpty()) o.addProperty("note", String.join(" ", notes));
            return o;
        } catch (IOException e) {
            throw new AgentError("RECORD_FAILED", "Could not write the video: " + e.getMessage(), "Check free disk space and that savePath is writable.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentError("RECORD_FAILED", "Interrupted while finishing the video.", "Record it again.");
        }
    }

    private static Path muxTemp(Track t, FfmpegArgs.Container c) {
        return t.videoTemp.resolveSibling(t.videoTemp.getFileName() + ".mux." + extension(c));
    }

    private AgentError failed(Session s, Track t, String what) {
        String log;
        synchronized (t.stderr) {
            log = String.join("\n", t.stderr);
        }
        String hint = s.container == FfmpegArgs.Container.MP4
                ? "h265 needs an ffmpeg built with libx265; otherwise check the ffmpeg output above."
                : "Check the ffmpeg output above; " + FfmpegArgs.requiredEncoder(s.container) + " must be in ffmpeg -encoders.";
        return new AgentError("RECORD_FAILED", what + (log.isEmpty() ? "." : ":\n" + log), hint);
    }

    private static void readStderr(Track t) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(t.process.getErrorStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                synchronized (t.stderr) {
                    t.stderr.addLast(line);
                    while (t.stderr.size() > 20) t.stderr.removeFirst();
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
        GuiScale.apply(mc, saved.guiScale());
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTemps(Session s) {
        List<Path> temps = new ArrayList<>();
        if (s.wavTemp != null) temps.add(s.wavTemp);
        for (Track t : s.tracks) {
            if (t.videoTemp == null) continue;
            temps.add(t.videoTemp);
            temps.add(muxTemp(t, s.container));
        }
        for (Path p : temps) {
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
