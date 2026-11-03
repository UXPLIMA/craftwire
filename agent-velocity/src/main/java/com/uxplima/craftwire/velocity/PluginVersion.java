package com.uxplima.craftwire.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The plugin's version, written into the jar at build time (the hello reports it to the hub). */
final class PluginVersion {
    static final String VERSION;

    static {
        String version = "dev";
        try (InputStream in = PluginVersion.class.getResourceAsStream("/craftwire-velocity.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                version = p.getProperty("version", version);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        VERSION = version;
    }

    private PluginVersion() {}
}
