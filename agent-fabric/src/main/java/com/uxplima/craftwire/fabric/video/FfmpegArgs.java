package com.uxplima.craftwire.fabric.video;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** ffmpeg command lines: raw frames on stdin to an MP4, and the MP4 muxed with the recorded sound. */
public final class FfmpegArgs {
    private FfmpegArgs() {}

    /** Frames arrive as RGBA rows bottom-up (as read back from the GPU), hence the vflip. */
    public static List<String> video(String ffmpeg, int width, int height, VideoSettings s, Path out) {
        int[] size = VideoSettings.outputSize(width, height, s.maxHeight());
        String filters = "vflip";
        if (size[1] != (height & ~1)) filters += ",scale=" + size[0] + ":" + size[1] + ":flags=lanczos";
        else if (size[0] != width || size[1] != height) filters += ",crop=" + size[0] + ":" + size[1] + ":0:0";
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-framerate", Integer.toString(s.fps()), "-i", "-",
                "-vf", filters));
        if (s.codec().equals("h265")) {
            a.addAll(List.of("-c:v", "libx265", "-preset", s.speed(), "-crf", Integer.toString(s.crf()), "-tag:v", "hvc1", "-x265-params", "log-level=error"));
        } else {
            a.addAll(List.of("-c:v", "libx264", "-preset", s.speed(), "-crf", Integer.toString(s.crf())));
        }
        a.addAll(List.of("-pix_fmt", "yuv420p", "-movflags", "+faststart", out.toString()));
        return a;
    }

    public static List<String> mux(String ffmpeg, Path video, Path wav, int audioKbps, Path out) {
        return List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", video.toString(), "-i", wav.toString(),
                "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", audioKbps + "k",
                "-movflags", "+faststart", out.toString());
    }
}
