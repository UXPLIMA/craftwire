package com.uxplima.craftwire.fabric.video;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** ffmpeg command lines: raw frames on stdin to a video (MP4, or ProRes .mov / VP9 .webm with alpha), and the video muxed with the recorded sound. */
public final class FfmpegArgs {
    private FfmpegArgs() {}

    /** The output file's container, from its extension. */
    public enum Container {
        MP4, MOV, WEBM;

        public static Container of(Path p) {
            String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            return n.endsWith(".mov") ? MOV : n.endsWith(".webm") ? WEBM : MP4;
        }
    }

    /** Whether {@code ffmpeg -encoders} output lists exactly this encoder (its second column). */
    public static boolean listsEncoder(String encoders, String name) {
        for (String line : encoders.split("\n")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length >= 2 && cols[1].equals(name)) return true;
        }
        return false;
    }

    public static String requiredEncoder(Container c) {
        return switch (c) {
            case MOV -> "prores_ks";
            case WEBM -> "libvpx-vp9";
            case MP4 -> "libx264";
        };
    }

    public static List<String> video(String ffmpeg, int width, int height, VideoSettings s, Path out) {
        return video(ffmpeg, width, height, s, out, Container.MP4);
    }

    /** Frames arrive as RGBA rows bottom-up (as read back from the GPU), hence the vflip. */
    public static List<String> video(String ffmpeg, int width, int height, VideoSettings s, Path out, Container c) {
        int[] size = VideoSettings.outputSize(width, height, s.maxHeight());
        String filters = "vflip";
        if (size[1] != (height & ~1)) filters += ",scale=" + size[0] + ":" + size[1] + ":flags=lanczos";
        else if (size[0] != width || size[1] != height) filters += ",crop=" + size[0] + ":" + size[1] + ":0:0";
        // The GUI canvas holds premultiplied colour; ffmpeg's encoders expect straight alpha.
        if (c != Container.MP4) filters += ",unpremultiply=inplace=1";
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-framerate", Integer.toString(s.fps()), "-i", "-",
                "-vf", filters));
        switch (c) {
            case MOV -> a.addAll(List.of("-c:v", "prores_ks", "-profile:v", "4444", "-pix_fmt", "yuva444p10le", "-alpha_bits", "16", out.toString()));
            case WEBM -> a.addAll(List.of("-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-crf", Integer.toString(s.crf()), "-b:v", "0",
                    "-row-mt", "1", "-auto-alt-ref", "0", out.toString()));
            case MP4 -> {
                if (s.codec().equals("h265")) {
                    a.addAll(List.of("-c:v", "libx265", "-preset", s.speed(), "-crf", Integer.toString(s.crf()), "-tag:v", "hvc1", "-x265-params", "log-level=error"));
                } else {
                    a.addAll(List.of("-c:v", "libx264", "-preset", s.speed(), "-crf", Integer.toString(s.crf())));
                }
                a.addAll(List.of("-pix_fmt", "yuv420p", "-movflags", "+faststart", out.toString()));
            }
        }
        return a;
    }

    public static List<String> mux(String ffmpeg, Path video, Path wav, int audioKbps, Path out) {
        return mux(ffmpeg, video, wav, audioKbps, out, Container.MP4);
    }

    public static List<String> mux(String ffmpeg, Path video, Path wav, int audioKbps, Path out, Container c) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", video.toString(), "-i", wav.toString(),
                "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy"));
        a.addAll(c == Container.WEBM ? List.of("-c:a", "libopus", "-b:a", audioKbps + "k") : List.of("-c:a", "aac", "-b:a", audioKbps + "k"));
        if (c == Container.MP4) a.addAll(List.of("-movflags", "+faststart"));
        a.add(out.toString());
        return a;
    }
}
