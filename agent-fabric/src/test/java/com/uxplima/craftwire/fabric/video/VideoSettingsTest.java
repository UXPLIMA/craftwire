package com.uxplima.craftwire.fabric.video;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoSettingsTest {
    static VideoSettings parse(String json) {
        return VideoSettings.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void highIsTheDefault() {
        VideoSettings s = parse("{}");
        assertEquals("high", s.preset());
        assertEquals(20, s.crf());
        assertEquals("medium", s.speed());
        assertEquals(30, s.fps());
        assertNull(s.maxHeight());
        assertEquals("h264", s.codec());
        assertTrue(s.audio());
        assertEquals(192, s.audioKbps());
    }

    @Test
    void eachPresetHasItsOwnTradeOff() {
        VideoSettings max = parse("{preset:'max'}");
        assertEquals(16, max.crf());
        assertEquals("slow", max.speed());
        assertEquals(60, max.fps());
        assertEquals(256, max.audioKbps());
        VideoSettings balanced = parse("{preset:'balanced'}");
        assertEquals(23, balanced.crf());
        assertEquals("veryfast", balanced.speed());
        VideoSettings light = parse("{preset:'light'}");
        assertEquals(28, light.crf());
        assertEquals(720, light.maxHeight());
        assertEquals(96, light.audioKbps());
    }

    @Test
    void explicitValuesOverrideThePreset() {
        VideoSettings s = parse("{preset:'light', fps:60, resolution:'source', crf:18, speed:'faster', codec:'h265', audio:false, audioBitrate:128}");
        assertEquals(60, s.fps());
        assertNull(s.maxHeight());
        assertEquals(18, s.crf());
        assertEquals("faster", s.speed());
        assertEquals("h265", s.codec());
        assertFalse(s.audio());
        assertEquals(128, s.audioKbps());
        assertEquals(1080, parse("{resolution:'1080p'}").maxHeight());
    }

    @Test
    void outOfRangeValuesAreRefused() {
        for (String bad : List.of("{preset:'ultra'}", "{fps:5}", "{fps:121}", "{crf:52}", "{speed:'warp'}", "{codec:'vp9'}",
                "{resolution:'999p'}", "{audioBitrate:16}")) {
            AgentError e = assertThrows(AgentError.class, () -> parse(bad), bad);
            assertEquals("INVALID_PARAMS", e.code(), bad);
        }
    }

    @Test
    void theOutputOnlyShrinksAndStaysEven() {
        assertArrayEquals(new int[] {1920, 1080}, VideoSettings.outputSize(1920, 1080, null));
        assertArrayEquals(new int[] {1280, 720}, VideoSettings.outputSize(1920, 1080, 720));
        assertArrayEquals(new int[] {854, 480}, VideoSettings.outputSize(1920, 1080, 480));   // 853.3: the nearest even width
        assertArrayEquals(new int[] {1280, 720}, VideoSettings.outputSize(1280, 720, 1080), "never scales up");
        assertArrayEquals(new int[] {1366, 766}, VideoSettings.outputSize(1367, 767, null), "odd sizes lose a pixel");
    }

    @Test
    void videoArgumentsFeedRawFramesAndFlipThem() {
        VideoSettings s = parse("{preset:'balanced'}");
        List<String> a = FfmpegArgs.video("ffmpeg", 1920, 1080, s, Path.of("out.mp4"));
        assertEquals("ffmpeg", a.get(0));
        assertSequence(a, "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "1920x1080", "-framerate", "30", "-i", "-");
        assertSequence(a, "-vf", "vflip");
        assertSequence(a, "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p");
        assertSequence(a, "-movflags", "+faststart");
        assertEquals(Path.of("out.mp4").toString(), a.get(a.size() - 1));
    }

    @Test
    void scalingAndCroppingAreAddedOnlyWhenNeeded() {
        assertSequence(FfmpegArgs.video("ffmpeg", 1920, 1080, parse("{preset:'light'}"), Path.of("o.mp4")), "-vf", "vflip,scale=1280:720:flags=lanczos");
        assertSequence(FfmpegArgs.video("ffmpeg", 1367, 767, parse("{}"), Path.of("o.mp4")), "-vf", "vflip,crop=1366:766:0:0");
    }

    @Test
    void h265IsTaggedSoApplePlayersOpenIt() {
        List<String> a = FfmpegArgs.video("ffmpeg", 1280, 720, parse("{codec:'h265'}"), Path.of("o.mp4"));
        assertSequence(a, "-c:v", "libx265");
        assertSequence(a, "-tag:v", "hvc1");
    }

    @Test
    void muxCopiesTheVideoAndEncodesTheSound() {
        List<String> a = FfmpegArgs.mux("ffmpeg", Path.of("v.mp4"), Path.of("a.wav"), 160, Path.of("out.mp4"));
        assertSequence(a, "-i", Path.of("v.mp4").toString(), "-i", Path.of("a.wav").toString());
        assertSequence(a, "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "160k");
        assertEquals(Path.of("out.mp4").toString(), a.get(a.size() - 1));
    }

    static void assertSequence(List<String> args, String... seq) {
        int i = java.util.Collections.indexOfSubList(args, List.of(seq));
        assertTrue(i >= 0, "expected " + List.of(seq) + " in " + args);
    }

    @SuppressWarnings("unused")
    private static JsonObject obj(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }
}
