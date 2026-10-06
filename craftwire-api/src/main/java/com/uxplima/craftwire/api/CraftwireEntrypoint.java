package com.uxplima.craftwire.api;

/**
 * Fabric: declare an entrypoint named {@code craftwire} in your {@code fabric.mod.json} that implements this, and
 * Craftwire calls it once the client has started. Your mod id is the tools' namespace.
 *
 * <pre>{@code "entrypoints": { "craftwire": ["com.example.MyCraftwireTools"] }}</pre>
 */
public interface CraftwireEntrypoint {
    void registerTools(ToolRegistry registry);
}
