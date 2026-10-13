package com.uxplima.craftwire.fabric.video;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.camera.CameraMotion;
import com.uxplima.craftwire.fabric.capture.GuiLayer;
import java.nio.file.Path;
import java.util.Locale;

/** record start's parameters, validated. {@code durationMs} is the effective length (given, or the camera motion's). */
public record RecordParams(Path savePath, VideoSettings settings, boolean hud, boolean chat, Long durationMs, int maxSeconds,
                           CameraMotion motion, boolean waitForTerrain, boolean waitForEnd, GuiLayer layer, boolean split, Integer guiScale) {
    public static final int MAX_SECONDS = 600;

    public static RecordParams parse(JsonObject p, CameraMotion motion) {
        String save = Params.optString(p, "savePath").orElseThrow(() -> Params.invalid("savePath is required (an .mp4 file)"));
        GuiLayer layer;
        try {
            layer = GuiLayer.parse(Params.optString(p, "layer").orElse(null));
        } catch (IllegalArgumentException e) {
            throw Params.invalid(e.getMessage());
        }
        String lower = save.toLowerCase(Locale.ROOT);
        if (layer == null && !lower.endsWith(".mp4")) throw Params.invalid("savePath must end in .mp4 (.mov or .webm only with a transparent layer)");
        if (layer != null && !(lower.endsWith(".mov") || lower.endsWith(".webm"))) {
            throw Params.invalid("a transparent layer needs .mov (ProRes 4444) or .webm (VP9); MP4 has no transparency");
        }
        if (layer != null && motion != null) throw Params.invalid("camera motions do not apply to a transparent layer (there is no world in it)");
        boolean split = Params.optBool(p, "split").orElse(false);
        if (split && layer != GuiLayer.GUI_TOOLTIP) throw Params.invalid("split needs layer 'gui+tooltip'");
        Integer guiScale = Params.optInt(p, "guiScale").orElse(null);
        if (guiScale != null && (guiScale < 1 || guiScale > 8)) throw Params.invalid("guiScale must be 1-8");
        VideoSettings settings = VideoSettings.parse(p);
        int maxSeconds = Params.optInt(p, "maxSeconds").orElse(120);
        if (maxSeconds < 1 || maxSeconds > MAX_SECONDS) throw Params.invalid("maxSeconds must be 1-" + MAX_SECONDS);
        Long duration = Params.optDouble(p, "durationMs").map(Math::round).orElse(motion == null ? null : motion.durationMs());
        if (duration != null && duration < 100) throw Params.invalid("durationMs must be at least 100");
        if (duration != null && duration > maxSeconds * 1000L) {
            throw Params.invalid("durationMs is longer than maxSeconds (" + maxSeconds + " s); raise maxSeconds (at most " + MAX_SECONDS + ")");
        }
        boolean wait = Params.optBool(p, "wait").orElse(false);
        if (wait && duration == null) throw Params.invalid("wait:true needs durationMs or a camera motion, so the recording ends by itself");
        return new RecordParams(Path.of(save).toAbsolutePath(), settings, Params.optBool(p, "hud").orElse(false),
                Params.optBool(p, "chat").orElse(true), duration, maxSeconds, motion, Params.optBool(p, "waitForTerrain").orElse(true), wait, layer, split, guiScale);
    }
}
