package com.uxplima.craftwire.api;

/**
 * A tool your plugin or mod adds to Craftwire. The AI sees it as {@code <namespace>_<name>}, where the namespace is
 * your plugin's name or your mod's id (lowercase), next to the built-in tools.
 *
 * <p>Arguments and results are JSON text, so this API needs no JSON library; use the one you already have (Gson
 * ships with Minecraft and Paper).
 */
public interface CraftwireTool {
    /** Lowercase letters, digits and underscores, at most 40 characters, e.g. {@code give_coins}. */
    String name();

    /** What the tool does and when to use it, written for the AI that calls it. */
    String description();

    /**
     * A JSON Schema object describing the arguments, e.g.
     * {@code {"type":"object","properties":{"player":{"type":"string"}},"required":["player"]}}.
     */
    default String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    /**
     * Runs the tool. {@code arguments} is the JSON object the AI passed. Return JSON (an object is best; any other
     * text is returned as a string). Throw {@link ToolException} for an expected failure the AI can act on; any other
     * exception is reported as a bug in your tool.
     */
    String call(String arguments) throws Exception;

    /**
     * Whether {@link #call} runs on the game thread (the server's main thread, or the client's render thread), where
     * game state may be read and changed. Return false for slow work that does not touch the game.
     */
    default boolean onGameThread() {
        return true;
    }
}
