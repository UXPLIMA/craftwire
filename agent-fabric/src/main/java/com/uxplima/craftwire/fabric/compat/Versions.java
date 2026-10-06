package com.uxplima.craftwire.fabric.compat;

import com.uxplima.craftwire.core.VersionOrder;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Picks the version-specific code for the running game: the newest supported version that is not newer than it.
 * Uses Fabric Loader's view of the version, so it is safe to call before Minecraft's own classes load (mixin plugins).
 */
public final class Versions {
    private Versions() {}

    private static final List<String> SUPPORTED = readSupported();
    private static final String SELECTED = VersionOrder.select(running(), SUPPORTED);

    /** The supported versions bundled in this jar, oldest first (from craftwire-compat.properties). */
    public static List<String> supported() {
        return SUPPORTED;
    }

    /** The bundled version whose code runs, e.g. "26.3". */
    public static String selected() {
        return SELECTED;
    }

    /** Package id of a version: 26.3 -> v26_3. */
    public static String id(String mc) {
        return "v" + mc.replace('.', '_');
    }

    static <T> T load(Class<T> type, String simpleName) {
        String name = "com.uxplima.craftwire.fabric.compat." + id(SELECTED) + "." + simpleName;
        try {
            return type.cast(Class.forName(name).getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Craftwire: no " + simpleName + " for Minecraft " + SELECTED, e);
        }
    }

    private static String running() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElseThrow();
    }

    private static List<String> readSupported() {
        Properties p = new Properties();
        try (InputStream in = Versions.class.getResourceAsStream("/craftwire-compat.properties")) {
            if (in == null) throw new IllegalStateException("craftwire-compat.properties missing from the agent jar");
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<String> out = new ArrayList<>();
        for (String v : p.getProperty("versions").split(",")) out.add(v.trim());
        return List.copyOf(out);
    }
}
