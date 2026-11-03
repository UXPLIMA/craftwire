package com.uxplima.craftwire.botrunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The runner's version and the Minecraft versions its Java bots can play, written into the jar at build time. */
final class RunnerVersion {
    static final String VERSION;
    static final String MC_VERSIONS;

    static {
        Properties p = new Properties();
        try (InputStream in = RunnerVersion.class.getResourceAsStream("/craftwire-botrunner.properties")) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        VERSION = p.getProperty("version", "dev");
        MC_VERSIONS = p.getProperty("mc_versions", "");
    }

    private RunnerVersion() {}
}
