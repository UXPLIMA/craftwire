package com.uxplima.craftwire.botrunner.java;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * One Minecraft version's class loader: the server jar, its bundled libraries and the javabot jars. Child first, so
 * the version gets the Netty, Guava and DataFixerUpper it was built with; parent first only for what the runner and
 * the version share across the boundary (the JDK, the runner's bot model, Gson and log4j).
 */
final class VersionLoader extends URLClassLoader {
    private static final List<String> SHARED = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "com.google.gson.", "org.apache.logging.log4j.",
            "com.uxplima.craftwire.core.", "com.uxplima.craftwire.api.", "com.uxplima.craftwire.bot.", "com.uxplima.craftwire.script.",
            "com.uxplima.craftwire.build.", "com.uxplima.craftwire.botrunner.");
    /** Inside the runner's namespace but part of the version: the javabot code itself. */
    private static final String OWN = "com.uxplima.craftwire.botrunner.javabot.";

    static {
        registerAsParallelCapable();
    }

    VersionLoader(String version, URL[] urls, ClassLoader parent) {
        super("minecraft-" + version, urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (shared(name)) return super.loadClass(name, resolve);
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException e) {
                    c = super.loadClass(name, false);
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    private static boolean shared(String name) {
        if (name.startsWith(OWN)) return false;
        for (String p : SHARED) if (name.startsWith(p)) return true;
        return false;
    }
}
