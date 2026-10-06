package com.uxplima.craftwire.core.profile;

/** Who a stack frame's code belongs to: a plugin or mod by name, or a category such as minecraft or java. */
public record Owner(String name, String kind) {
    public static final String PLUGIN = "plugin";
    public static final String MOD = "mod";

    public static final Owner MINECRAFT = new Owner("minecraft", "minecraft");
    public static final Owner SERVER = new Owner("server", "server");
    public static final Owner LOADER = new Owner("fabric-loader", "loader");
    public static final Owner JAVA = new Owner("java", "java");
    public static final Owner CRAFTWIRE = new Owner("craftwire", "craftwire");
    public static final Owner LIBRARY = new Owner("library", "library");

    /** A plugin's or mod's own code, as opposed to the game, the server, Java or a shared library. */
    public boolean isAddon() {
        return kind.equals(PLUGIN) || kind.equals(MOD);
    }
}
