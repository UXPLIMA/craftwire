package com.uxplima.craftwire.fabric.video;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.core.AgentError;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegLocatorTest {
    @TempDir Path dir;

    Path touch(String rel) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "");
        return p;
    }

    @Test
    void theEnvironmentVariableWins() throws IOException {
        Path mine = touch("custom/ffmpeg.exe");
        Path onPath = touch("bin/ffmpeg.exe");
        Map<String, String> env = Map.of("CRAFTWIRE_FFMPEG", mine.toString(), "PATH", onPath.getParent().toString());
        assertEquals(Optional.of(mine), FfmpegLocator.find(env, "Windows 11", p -> true));
    }

    @Test
    void searchesThePathWithTheWindowsExeName() throws IOException {
        Path onPath = touch("bin/ffmpeg.exe");
        Map<String, String> env = Map.of("PATH", dir.resolve("nothing") + File.pathSeparator + onPath.getParent());
        assertEquals(Optional.of(onPath), FfmpegLocator.find(env, "Windows 11", p -> true));
    }

    @Test
    void findsAWingetInstallTheGameCannotSeeOnItsPath() throws IOException {
        Path winget = touch("Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-8.1.1-full_build/bin/ffmpeg.exe");
        Map<String, String> env = Map.of("PATH", "", "LOCALAPPDATA", dir.toString());
        assertEquals(Optional.of(winget), FfmpegLocator.find(env, "Windows 11", p -> true));
    }

    @Test
    void onUnixThePlainNameIsUsed() throws IOException {
        Path onPath = touch("usr/bin/ffmpeg");
        Map<String, String> env = Map.of("PATH", onPath.getParent().toString());
        assertEquals(Optional.of(onPath), FfmpegLocator.find(env, "Linux", p -> true));
    }

    @Test
    void aFileThatDoesNotRunIsSkipped() throws IOException {
        Path broken = touch("a/ffmpeg");
        Path good = touch("b/ffmpeg");
        Map<String, String> env = Map.of("PATH", broken.getParent() + File.pathSeparator + good.getParent());
        assertEquals(Optional.of(good), FfmpegLocator.find(env, "Linux", p -> p.equals(good)));
    }

    @Test
    void notFoundTellsHowToInstallItOnThisSystem() {
        AgentError win = FfmpegLocator.notFound("Windows 11", Map.of());
        assertEquals("FFMPEG_NOT_FOUND", win.code());
        assertTrue(win.hint().contains("winget install"), win.hint());
        assertTrue(FfmpegLocator.notFound("Mac OS X", Map.of()).hint().contains("brew install ffmpeg"));
        assertTrue(FfmpegLocator.notFound("Linux", Map.of()).hint().contains("apt install ffmpeg"));
        AgentError env = FfmpegLocator.notFound("Linux", Map.of("CRAFTWIRE_FFMPEG", "/nope/ffmpeg"));
        assertTrue(env.getMessage().contains("/nope/ffmpeg"), env.getMessage());
    }
}
