package com.uxplima.craftwire.fabric.video;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.camera.CameraMotion;
import java.nio.file.Path;
import java.util.Locale;

/** record start's parameters, validated. {@code durationMs} is the effective length (given, or the camera motion's). */
public record RecordParams(Path savePath, VideoSettings settings, boolean hud, boolean chat, Long durationMs, int maxSeconds,
                           CameraMotion motion, boolean waitForTerrain, boolean waitForEnd) {
    public static final int MAX_SECONDS = 600;

    public static RecordParams parse(JsonObject p, CameraMotion motion) {
        String save = Params.optString(p, "savePath").orElseThrow(() -> Params.invalid("savePath is required (an .mp4 file)"));
        if (!save.toLowerCase(Locale.ROOT).endsWith(".mp4")) throw Params.invalid("savePath must end in .mp4");
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
                Params.optBool(p, "chat").orElse(true), duration, maxSeconds, motion, Params.optBool(p, "waitForTerrain").orElse(true), wait);
    }
}
