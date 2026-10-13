package com.uxplima.craftwire.fabric.video;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FfmpegArgsTest {
    @Test
    void movIsProRes4444WithAlpha() {
        var a = FfmpegArgs.video("ffmpeg", 1920, 1080, VideoSettings.parse(new com.google.gson.JsonObject()), java.nio.file.Path.of("o.mov"), FfmpegArgs.Container.MOV);
        assertTrue(String.join(" ", a).contains("-c:v prores_ks -profile:v 4444 -pix_fmt yuva444p10le"), String.join(" ", a));
        assertFalse(a.contains("+faststart"));
    }

    @Test
    void webmIsVp9WithAlpha() {
        var a = FfmpegArgs.video("ffmpeg", 1920, 1080, VideoSettings.parse(new com.google.gson.JsonObject()), java.nio.file.Path.of("o.webm"), FfmpegArgs.Container.WEBM);
        String s = String.join(" ", a);
        assertTrue(s.contains("-c:v libvpx-vp9 -pix_fmt yuva420p") && s.contains("-b:v 0") && s.contains("-crf "), s);
    }

    @Test
    void soundIsAacInMovAndOpusInWebm() {
        assertTrue(String.join(" ", FfmpegArgs.mux("f", java.nio.file.Path.of("v.mov"), java.nio.file.Path.of("a.wav"), 192, java.nio.file.Path.of("o.mov"), FfmpegArgs.Container.MOV)).contains("-c:a aac"));
        assertTrue(String.join(" ", FfmpegArgs.mux("f", java.nio.file.Path.of("v.webm"), java.nio.file.Path.of("a.wav"), 192, java.nio.file.Path.of("o.webm"), FfmpegArgs.Container.WEBM)).contains("-c:a libopus"));
    }

    /** Review Focus #4. */
    @Test
    void missingEncoderIsNamed() {
        assertEquals("prores_ks", FfmpegArgs.requiredEncoder(FfmpegArgs.Container.MOV));
        assertEquals("libvpx-vp9", FfmpegArgs.requiredEncoder(FfmpegArgs.Container.WEBM));
    }

    @Test
    void encoderListIsReadExactly() {
        String list = " V....D libx264              libx264 H.264\n V....D prores_ks            Apple ProRes (iCodec Pro)\n";
        assertTrue(FfmpegArgs.listsEncoder(list, "prores_ks"));
        assertFalse(FfmpegArgs.listsEncoder(list, "libvpx-vp9"));
        assertFalse(FfmpegArgs.listsEncoder(list, "prores"));   // a prefix is not the encoder
    }
}
