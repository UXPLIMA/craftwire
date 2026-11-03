package com.uxplima.craftwire.botrunner.java;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which side of the version boundary a class comes from. The version's jars hold their own copies of classes the
 * runner also has (Netty, and here a fake copy of the bot model): the version must get its own libraries, but the
 * runner's copy of everything the two sides pass between them, or the bot's objects would not cast.
 */
class VersionLoaderTest {
    @TempDir
    Path dir;

    /** Compiles `source` (one public class) into the version's class directory. */
    private void compile(String className, String source) throws IOException {
        Path src = dir.resolve("src").resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "-d", dir.resolve("classes").toString(), src.toString()), "compiles " + className);
    }

    private VersionLoader loader() throws IOException {
        return new VersionLoader("test", new URL[] {dir.resolve("classes").toUri().toURL()}, getClass().getClassLoader());
    }

    @Test
    void theVersionGetsItsOwnLibrariesButTheRunnersSharedTypes() throws Exception {
        // a class the runner's Netty has too, a bot model type, a runner type, and the javabot code
        compile("io.netty.util.Version", "package io.netty.util; public class Version { }");
        compile("com.uxplima.craftwire.bot.model.Position", "package com.uxplima.craftwire.bot.model; public class Position { }");
        compile("com.uxplima.craftwire.botrunner.java.JavaBotFactory",
                "package com.uxplima.craftwire.botrunner.java; public class JavaBotFactory { }");
        compile("com.uxplima.craftwire.botrunner.javabot.Probe", "package com.uxplima.craftwire.botrunner.javabot; public class Probe { }");

        try (VersionLoader v = loader()) {
            ClassLoader runner = getClass().getClassLoader();
            assertSame(v, v.loadClass("io.netty.util.Version").getClassLoader(), "the version's own Netty");
            assertSame(v, v.loadClass("com.uxplima.craftwire.botrunner.javabot.Probe").getClassLoader(), "javabot code is the version's");
            for (String shared : List.of("com.uxplima.craftwire.bot.model.Position", "com.uxplima.craftwire.botrunner.java.JavaBotFactory",
                    "com.google.gson.JsonObject", "org.apache.logging.log4j.Logger", "java.lang.String")) {
                assertSame(runner.loadClass(shared), v.loadClass(shared), shared + " is the runner's");
            }
        }
    }

    @Test
    void classesTheVersionDoesNotHaveComeFromTheRunner() throws Exception {
        Files.createDirectories(dir.resolve("classes"));
        try (VersionLoader v = loader()) {
            assertSame(getClass().getClassLoader().loadClass("io.netty.buffer.ByteBuf"), v.loadClass("io.netty.buffer.ByteBuf"));
            assertThrows(ClassNotFoundException.class, () -> v.loadClass("net.minecraft.NotThere"));
        }
    }
}
