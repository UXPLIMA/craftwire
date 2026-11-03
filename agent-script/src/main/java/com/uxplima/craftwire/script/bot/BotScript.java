package com.uxplima.craftwire.script.bot;

import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.script.ScriptEngine;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyObject;

/**
 * One bot_behavior {type:'script'}: a GraalJS context shared by all bots it was given, reaching only the `bots`
 * API (no Java classes, files or threads), so it needs allow-bots but not allow-eval. Handlers run one at a time
 * under this object's lock, from whichever bot thread ticks; a handler that runs longer than {@link #CALL_LIMIT_MS}
 * is cancelled and the script stops for every bot.
 */
public final class BotScript implements AutoCloseable {
    /** A handler runs on a server tick: 200 ms already costs four ticks. */
    public static final long CALL_LIMIT_MS = 200;
    private static final long LOAD_LIMIT_MS = 2000;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "craftwire-bot-script-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final ClassLoader loader;
    private final ScriptBots bots = new ScriptBots();
    private final Engine engine;
    private final Context context;
    private volatile String failure;

    private BotScript(ClassLoader loader) {
        this.loader = loader;
        this.engine = Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
        this.context = Context.newBuilder("js").engine(engine)
                .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT).allowArrayAccess(true).allowListAccess(true).build())
                .allowIO(IOAccess.NONE).allowCreateThread(false).allowHostClassLookup(c -> false).build();
    }

    /** Loads the script; its top level registers handlers with bots.onTick / bots.onMessage. */
    public static BotScript load(String code, ClassLoader loader) {
        BotScript s = withLoader(loader, () -> new BotScript(loader));
        s.context.getBindings("js").putMember("bots", s.bots);
        s.guarded(LOAD_LIMIT_MS, () -> s.context.eval(Source.newBuilder("js", code, "behavior.js").buildLiteral()));
        if (s.failure != null) {
            String why = s.failure;
            s.close();
            throw new AgentError("SCRIPT_ERROR", why,
                    "Fix the script: register handlers with bots.onTick((bot, tick) => …) or bots.onMessage((bot, msg) => …).");
        }
        if (s.bots.tickHandlers().isEmpty() && s.bots.messageHandlers().isEmpty()) {
            s.close();
            throw new AgentError("SCRIPT_ERROR", "The script registered no handler", "Call bots.onTick(fn) or bots.onMessage(fn) at the top level.");
        }
        return s;
    }

    ScriptBot attach(String name) {
        return bots.attach(name);
    }

    /** Detaches a bot; the last one out closes the context. */
    synchronized void detach(String name) {
        bots.detach(name);
        if (bots.count() == 0) close();
    }

    String failure() {
        return failure;
    }

    String takeLog() {
        return bots.takeLog();
    }

    synchronized void tick(ScriptBot bot, long tick) {
        for (Value fn : bots.tickHandlers()) {
            if (failure != null) return;
            guarded(CALL_LIMIT_MS, () -> fn.execute(bot, tick));
        }
    }

    synchronized void message(ScriptBot bot, Message m) {
        if (bots.messageHandlers().isEmpty()) return;
        Map<String, Object> o = new HashMap<>();
        o.put("id", m.id());
        o.put("kind", m.kind());
        o.put("text", m.text());
        o.put("sender", m.sender());
        ProxyObject msg = ProxyObject.fromMap(o);
        for (Value fn : bots.messageHandlers()) {
            if (failure != null) return;
            guarded(CALL_LIMIT_MS, () -> fn.execute(bot, msg));
        }
    }

    /** Runs a call under the watchdog; an error or a timeout marks the script failed instead of reaching the tick. */
    private void guarded(long limitMs, Runnable call) {
        if (failure != null) return;
        ScheduledFuture<?> kill = WATCHDOG.schedule(() -> context.close(true), limitMs, TimeUnit.MILLISECONDS);
        try {
            withLoader(loader, () -> {
                call.run();
                return null;
            });
        } catch (PolyglotException e) {
            failure = e.isCancelled() ? "A handler ran longer than " + limitMs + " ms and the script was stopped" : ScriptEngine.describe(e);
        } catch (IllegalStateException e) {
            failure = "The script was stopped: " + e.getMessage();
        } finally {
            kill.cancel(false);
        }
    }

    private static <T> T withLoader(ClassLoader loader, Supplier<T> work) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);   // Truffle discovers GraalJS through the context class loader
        try {
            return work.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Override
    public synchronized void close() {
        if (failure == null) failure = "closed";
        context.close(true);
        engine.close();
    }
}
