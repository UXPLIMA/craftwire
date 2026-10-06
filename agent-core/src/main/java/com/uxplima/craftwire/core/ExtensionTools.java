package com.uxplima.craftwire.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.uxplima.craftwire.api.CraftwireTool;
import com.uxplima.craftwire.api.ToolException;
import com.uxplima.craftwire.api.ToolRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The tools plugins and mods add through the API, by full name ({@code <namespace>_<name>}). The agent sends the
 * list to the hub whenever it changes ({@code onChange}) and runs calls ({@code ext.call}) on the game thread unless a
 * tool opts out.
 */
public final class ExtensionTools {
    /** Runs work on the game thread of this loader. */
    public interface GameThread {
        <T> CompletableFuture<T> run(Callable<T> work);
    }

    private record Entry(String namespace, CraftwireTool tool, JsonObject schema) {}

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,40}");

    private final Map<String, Entry> tools = new ConcurrentSkipListMap<>();
    private final GameThread gameThread;
    private final Executor background;
    private final Runnable onChange;
    private final BiConsumer<String, Throwable> logError;

    public ExtensionTools(GameThread gameThread, Executor background, Runnable onChange, BiConsumer<String, Throwable> logError) {
        this.gameThread = gameThread;
        this.background = background;
        this.onChange = onChange;
        this.logError = logError;
    }

    /** A registry whose tools go under {@code owner}'s namespace (a plugin name or mod id, made lowercase). */
    public ToolRegistry registryFor(String owner) {
        String namespace = namespace(owner);
        return new ToolRegistry() {
            @Override public void register(CraftwireTool tool) { ExtensionTools.this.register(namespace, tool); }
            @Override public void unregister(CraftwireTool tool) { ExtensionTools.this.unregister(namespace, tool); }
        };
    }

    /** Lowercase letters, digits and underscores, at most 24 characters. */
    public static String namespace(String owner) {
        String ns = owner.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (ns.isEmpty()) ns = "ext";
        return ns.length() <= 24 ? ns : ns.substring(0, 24);
    }

    public void register(String namespace, CraftwireTool tool) {
        String name = tool.name();
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Craftwire tool names are lowercase letters, digits and underscores (1-40): " + name);
        }
        tools.put(namespace + "_" + name, new Entry(namespace, tool, schema(tool)));
        onChange.run();
    }

    public void unregister(String namespace, CraftwireTool tool) {
        String full = namespace + "_" + tool.name();
        Entry e = tools.get(full);
        if (e != null && e.tool() == tool && tools.remove(full, e)) onChange.run();
    }

    /** Removes the tools that match, e.g. those of a plugin that was disabled. */
    public void unregisterIf(Predicate<CraftwireTool> match) {
        if (tools.values().removeIf(e -> match.test(e.tool()))) onChange.run();
    }

    /** [{name, namespace, description, inputSchema}] for the hub. */
    public JsonArray list() {
        JsonArray out = new JsonArray();
        tools.forEach((full, e) -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", full);
            o.addProperty("namespace", e.namespace());
            o.addProperty("description", e.tool().description());
            o.add("inputSchema", e.schema().deepCopy());
            out.add(o);
        });
        return out;
    }

    /** ext.call {tool, args}: the tool's JSON result (text that is not JSON comes back as a string). */
    public CompletableFuture<JsonElement> call(JsonObject params) {
        String full = params.has("tool") ? params.get("tool").getAsString() : "";
        Entry e = tools.get(full);
        if (e == null) {
            return CompletableFuture.failedFuture(new AgentError("EXTENSION_NOT_FOUND", "No extension tool named " + full,
                    "The plugin or mod that added it was disabled or removed; list_instances shows the tools each instance has."));
        }
        String args = params.has("args") && params.get("args").isJsonObject() ? params.get("args").toString() : "{}";
        Callable<String> work = () -> e.tool().call(args);
        CompletableFuture<String> run = e.tool().onGameThread()
                ? gameThread.run(work)
                : CompletableFuture.supplyAsync(() -> {
                    try {
                        return work.call();
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, background);
        return run.handle((text, err) -> {
            if (err == null) return parse(text);
            Throwable t = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
            if (t instanceof ToolException te) throw new AgentError(te.code(), te.getMessage(), te.hint());
            if (t instanceof AgentError ae) throw ae;
            logError.accept("Craftwire extension tool " + full + " failed", t);
            throw new AgentError("EXTENSION_FAILED", full + " threw " + t.getClass().getName() + ": " + t.getMessage(),
                    "This is a bug in the " + e.namespace() + " tool, not in the arguments; its stack trace is in the log (exceptions tool).");
        });
    }

    private static JsonElement parse(String text) {
        if (text == null) return JsonNull.INSTANCE;
        try {
            return JsonParser.parseString(text);
        } catch (JsonParseException ex) {
            return new JsonPrimitive(text);
        }
    }

    private static JsonObject schema(CraftwireTool tool) {
        String text = tool.inputSchema();
        JsonElement s;
        try {
            s = JsonParser.parseString(text == null ? "{}" : text);
        } catch (JsonParseException ex) {
            throw new IllegalArgumentException("The input schema of Craftwire tool " + tool.name() + " is not JSON: " + ex.getMessage());
        }
        if (!s.isJsonObject()) throw new IllegalArgumentException("The input schema of Craftwire tool " + tool.name() + " must be a JSON object");
        JsonObject o = s.getAsJsonObject();
        if (o.has("type") && !"object".equals(o.get("type").getAsString())) {
            throw new IllegalArgumentException("The input schema of Craftwire tool " + tool.name() + " must have type object (tool arguments are an object)");
        }
        if (!o.has("type")) o.addProperty("type", "object");
        return o;
    }
}
