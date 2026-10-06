package com.uxplima.craftwire.script;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.core.ScriptRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

/**
 * GraalJS for server_eval: one context per hub session, so globals survive between calls until reset.
 * A watchdog cancels a run after its timeout and the cancelled context is discarded.
 */
public final class ScriptEngine implements ScriptRunner {
    private final ClassLoader loader;
    private final String prelude;
    private final CapturedOutput output = new CapturedOutput();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "craftwire-eval-watchdog");
        t.setDaemon(true);
        return t;
    });
    private Engine engine;
    private Context context;
    private volatile boolean resetRequested;

    public ScriptEngine(ClassLoader loader, String prelude) {
        this.loader = loader;
        this.prelude = prelude;
    }

    /** Runs {@code code} on the calling thread and returns {result, output}. */
    public synchronized JsonObject eval(String code, long timeoutMs) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);   // Truffle discovers GraalJS through the context class loader
        try {
            if (resetRequested) {
                resetRequested = false;
                resetSession();
            }
            Context ctx = context();
            output.take();   // drop anything printed between calls (e.g. by a scheduled callback)
            Watch watch = new Watch();
            ScheduledFuture<?> kill = watchdog.schedule(() -> {
                if (watch.fire()) ctx.close(true);
            }, timeoutMs, TimeUnit.MILLISECONDS);
            try {
                Value value = ctx.eval(Source.newBuilder("js", code, "eval.js").buildLiteral());
                JsonObject r = new JsonObject();
                r.add("result", ValueJson.toJson(value));
                r.addProperty("output", output.take());
                return r;
            } catch (PolyglotException e) {
                if (e.isCancelled()) {
                    throw new AgentError("TIMEOUT", "The script ran longer than " + timeoutMs + " ms and was cancelled.",
                            "Globals were reset. Keep loops short on the server thread, or raise timeoutMs (max 60000).");
                }
                throw new AgentError("EVAL_ERROR", describe(e) + printed(output.take()),
                        "Fix the script and run it again. Globals set before the error are kept.");
            } finally {
                kill.cancel(false);
                if (watch.finish()) context = null;   // the watchdog fired: that context is (being) closed
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Decides, atomically, whether the watchdog or the finished eval got there first. Checking the
     * ScheduledFuture is not enough: cancel() still succeeds while the watchdog task is running.
     */
    private static final class Watch {
        private boolean done;
        private boolean fired;

        synchronized boolean fire() {
            if (!done) fired = true;
            return fired;
        }

        synchronized boolean finish() {
            done = true;
            return fired;
        }
    }

    /**
     * Starts the next eval with fresh globals, without waiting for one that is running now. For callers that must
     * not block, such as the hub connection's thread on reconnect.
     */
    public void requestReset() {
        resetRequested = true;
    }

    public synchronized void resetSession() {
        if (context != null) context.close(true);
        context = null;
    }

    @Override
    public synchronized void close() {
        resetSession();
        if (engine != null) engine.close();
        engine = null;
        watchdog.shutdownNow();
    }

    private Context context() {
        if (context == null) {
            if (engine == null) engine = Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
            Context ctx = Context.newBuilder("js").engine(engine).allowAllAccess(true)
                    .hostClassLoader(loader).out(output).err(output).build();
            if (!prelude.isEmpty()) ctx.eval("js", prelude);
            context = ctx;
        }
        return context;
    }

    static String describe(PolyglotException e) {
        StringBuilder b = new StringBuilder(String.valueOf(e.getMessage()));
        SourceSection at = e.getSourceLocation();
        if (at != null) b.append(" (line ").append(at.getStartLine()).append(", column ").append(at.getStartColumn()).append(')');
        if (e.isHostException()) b.append(" [Java ").append(e.asHostException().getClass().getName()).append(']');
        List<String> frames = new ArrayList<>();
        for (PolyglotException.StackFrame f : e.getPolyglotStackTrace()) {
            if (f.isGuestFrame()) frames.add("  at " + f);
            if (frames.size() == 8) break;
        }
        if (!frames.isEmpty()) b.append('\n').append(String.join("\n", frames));
        return b.toString();
    }

    private static String printed(String out) {
        return out.isEmpty() ? "" : "\nOutput before the error:\n" + out;
    }
}
