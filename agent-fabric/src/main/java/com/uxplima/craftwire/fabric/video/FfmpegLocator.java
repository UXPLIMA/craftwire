package com.uxplima.craftwire.fabric.video;

import com.uxplima.craftwire.core.AgentError;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Finds the user's ffmpeg. Besides CRAFTWIRE_FFMPEG and PATH it looks where installers put it, because a game
 * started from a launcher often has an older PATH than the user's shell (e.g. right after `winget install`).
 */
public final class FfmpegLocator {
    public static final String ENV = "CRAFTWIRE_FFMPEG";

    private FfmpegLocator() {}

    public static Optional<Path> find(Map<String, String> env, String osName, Predicate<Path> works) {
        String custom = env.get(ENV);
        if (custom != null && !custom.isBlank()) {
            Path p = Path.of(custom);
            return Files.isRegularFile(p) && works.test(p) ? Optional.of(p) : Optional.empty();
        }
        for (Path p : candidates(env, osName)) {
            if (Files.isRegularFile(p) && works.test(p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    private static List<Path> candidates(Map<String, String> env, String osName) {
        boolean windows = isWindows(osName);
        String exe = windows ? "ffmpeg.exe" : "ffmpeg";
        List<Path> out = new ArrayList<>();
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank()) out.add(Path.of(dir.trim()).resolve(exe));
        }
        if (windows) {
            String local = env.get("LOCALAPPDATA");
            if (local != null) {
                Path winget = Path.of(local, "Microsoft", "WinGet");
                out.add(winget.resolve("Links").resolve(exe));
                // winget install Gyan.FFmpeg: Packages\Gyan.FFmpeg_<source>\ffmpeg-<version>-full_build\bin\ffmpeg.exe
                for (Path pkg : list(winget.resolve("Packages"), "Gyan.FFmpeg*")) {
                    for (Path build : list(pkg, "ffmpeg-*")) out.add(build.resolve("bin").resolve(exe));
                }
            }
            out.add(Path.of("C:\\ffmpeg\\bin\\ffmpeg.exe"));
            String programFiles = env.get("ProgramFiles");
            if (programFiles != null) out.add(Path.of(programFiles, "ffmpeg", "bin", exe));
        } else {
            for (String p : List.of("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg", "/snap/bin/ffmpeg")) out.add(Path.of(p));
        }
        return out;
    }

    private static List<Path> list(Path dir, String glob) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, glob)) {
            s.forEach(out::add);
        } catch (IOException ignored) {
            // an unreadable folder is just not a place ffmpeg is in
        }
        out.sort(null);
        return out.reversed();   // newest version first
    }

    /** Whether {@code ffmpeg -version} runs. */
    public static boolean runs(Path ffmpeg) {
        try {
            Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static AgentError notFound(String osName, Map<String, String> env) {
        String custom = env.get(ENV);
        String install = isWindows(osName) ? "winget install Gyan.FFmpeg"
                : osName.toLowerCase(Locale.ROOT).contains("mac") ? "brew install ffmpeg"
                : "sudo apt install ffmpeg (or your distribution's package manager)";
        if (custom != null && !custom.isBlank()) {
            return new AgentError("FFMPEG_NOT_FOUND", ENV + " is set to " + custom + ", but that ffmpeg does not run.",
                    "Point " + ENV + " at a working ffmpeg executable, or unset it to search PATH. Install: " + install);
        }
        return new AgentError("FFMPEG_NOT_FOUND", "Video recording needs ffmpeg, and it was not found on PATH or in the usual install folders.",
                "Install it (" + install + ") and retry; or set " + ENV + " to the ffmpeg executable before starting the game.");
    }

    private static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
