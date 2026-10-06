package com.uxplima.craftwire.api;

/**
 * Where tools are added. On Paper, get it from the services manager:
 * {@code getServer().getServicesManager().load(ToolRegistry.class)} (add {@code softdepend: [Craftwire]} so it is
 * there when your plugin enables). On Fabric, Craftwire hands it to your {@link CraftwireEntrypoint}.
 *
 * <p>Changes reach the AI at once: connected AI clients are told the tool list changed.
 */
public interface ToolRegistry {
    /**
     * Adds a tool, or replaces one with the same name from the same plugin or mod.
     *
     * @throws IllegalArgumentException if the name is not lowercase letters, digits and underscores (1-40), or the
     *     schema is not a JSON object
     */
    void register(CraftwireTool tool);

    /** Removes a tool. On Paper, a plugin's tools are also removed when it is disabled. */
    void unregister(CraftwireTool tool);
}
