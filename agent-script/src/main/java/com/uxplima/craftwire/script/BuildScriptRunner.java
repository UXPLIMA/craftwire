package com.uxplima.craftwire.script;

import com.uxplima.craftwire.build.BuildApi;
import com.uxplima.craftwire.build.BuildContext;
import com.uxplima.craftwire.build.BuildError;
import com.uxplima.craftwire.build.EditBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

/**
 * Runs build scripts: a fresh GraalJS context per run that can reach only __cw (no Java classes, files or threads).
 * A file may declare parts with part(name, fn); without parts the whole script is one part.
 */
public final class BuildScriptRunner implements AutoCloseable {
    public record Part(String name, EditBuffer buffer) {}

    public record Result(List<Part> parts, String output) {}

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /**
     * Scripts may call BuildApi's own methods and nothing else: with HostAccess.ALL, __cw.getClass().getClassLoader()
     * reached reflection, which would let a build script run any Java on a server that turned server_eval off.
     */
    private static final HostAccess ACCESS = access();

    private static HostAccess access() {
        HostAccess.Builder b = HostAccess.newBuilder(HostAccess.EXPLICIT).allowArrayAccess(true).allowListAccess(true);
        for (Method m : BuildApi.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) b.allowAccess(m);
        }
        return b.build();
    }

    private final ClassLoader loader;
    private final String prelude;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "craftwire-build-watchdog");
        t.setDaemon(true);
        return t;
    });
    private Engine engine;

    public BuildScriptRunner(ClassLoader loader) {
        this.loader = loader;
        try (InputStream in = BuildScriptRunner.class.getResourceAsStream("/craftwire/build-api.js")) {
            this.prelude = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private synchronized Engine engine() {
        if (engine == null) engine = Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
        return engine;
    }

    public Result run(String code, String file, String onlyPart, String defaultPart, BuildContext ctx, long timeoutMs) {
        if (!NAME.matcher(defaultPart).matches()) throw badName(defaultPart);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        AtomicBoolean timedOut = new AtomicBoolean();
        try (Context js = Context.newBuilder("js").engine(engine()).allowHostAccess(ACCESS)
                .allowHostClassLookup(name -> false).allowIO(IOAccess.NONE).allowCreateThread(false)
                .out(OutputStreamSink.INSTANCE).err(OutputStreamSink.INSTANCE).build()) {
            ScheduledFuture<?> kill = watchdog.schedule(() -> {
                timedOut.set(true);
                js.close(true);
            }, timeoutMs, TimeUnit.MILLISECONDS);
            try {
                js.getBindings("js").putMember("__cw", new BuildApi(ctx));
                js.eval(Source.newBuilder("js", prelude, "build-api.js").buildLiteral());
                js.eval(Source.newBuilder("js", code, file == null ? "build.js" : file).buildLiteral());
                Value declared = js.getBindings("js").getMember("__parts");
                List<Part> parts = new ArrayList<>();
                if (declared.getArraySize() == 0) {
                    parts.add(new Part(defaultPart, ctx.buffer()));
                } else {
                    if (ctx.buffer().size() > 0) {
                        throw new BuildError("SCRIPT_ERROR", "Blocks were placed outside part(...) in a file that declares parts",
                                "Move every placement into a part(name, () => { ... }) block.");
                    }
                    List<String> names = new ArrayList<>();
                    for (long i = 0; i < declared.getArraySize(); i++) names.add(declared.getArrayElement(i).getArrayElement(0).asString());
                    if (onlyPart != null && !names.contains(onlyPart)) {
                        throw new BuildError("BAD_ARGUMENT", "No part named " + onlyPart + " (parts: " + String.join(", ", names) + ")", "Pass one of those names, or leave part out to run all.");
                    }
                    for (long i = 0; i < declared.getArraySize(); i++) {
                        Value entry = declared.getArrayElement(i);
                        String name = entry.getArrayElement(0).asString();
                        if (!NAME.matcher(name).matches()) throw badName(name);
                        if (onlyPart != null && !onlyPart.equals(name)) continue;
                        ctx.startPart();
                        entry.getArrayElement(1).executeVoid();
                        parts.add(new Part(name, ctx.buffer()));
                    }
                }
                if (ctx.bad().size() > 0) {
                    throw new BuildError("BAD_BLOCK", ctx.bad().message(), "Fix the block names (ids like stone_bricks, states like oak_stairs[facing=east]); nothing was written.");
                }
                return new Result(parts, ctx.output());
            } catch (PolyglotException e) {
                if (e.isCancelled() || timedOut.get()) {
                    throw new BuildError("TIMEOUT", "The script ran longer than " + timeoutMs + " ms and was cancelled; nothing was written.",
                            "Look for an endless loop, or raise timeoutMs (max 300000).");
                }
                if (e.isHostException() && e.asHostException() instanceof BuildError be) throw be;
                if (e.isHostException() && e.asHostException() instanceof IllegalArgumentException iae) {
                    throw new BuildError("SCRIPT_ERROR", iae.getMessage() + where(e), "Fix the call and run the build again; nothing was written.");
                }
                throw new BuildError("SCRIPT_ERROR", ScriptEngine.describe(e), "Fix the script and run the build again; nothing was written.");
            } finally {
                kill.cancel(false);
            }
        } catch (IllegalStateException closed) {
            if (timedOut.get()) {
                throw new BuildError("TIMEOUT", "The script ran longer than " + timeoutMs + " ms and was cancelled; nothing was written.",
                        "Look for an endless loop, or raise timeoutMs (max 300000).");
            }
            throw closed;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static String where(PolyglotException e) {
        return e.getSourceLocation() == null ? "" : " (line " + e.getSourceLocation().getStartLine() + ")";
    }

    private static BuildError badName(String name) {
        return new BuildError("BAD_ARGUMENT", "Bad part name '" + name + "'", "Part names use letters, digits, _ and - (at most 64).");
    }

    @Override
    public synchronized void close() {
        if (engine != null) engine.close();
        engine = null;
        watchdog.shutdownNow();
    }

    /** Scripts print through print(); console output is dropped. */
    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override public void write(int b) {}
    }
}
