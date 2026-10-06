package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import org.bukkit.Location;

public final class BotJson {
    private BotJson() {}

    public static JsonObject summary(Bot b) {
        Location l = b.bukkit().getLocation();
        JsonObject o = new JsonObject();
        o.addProperty("name", b.name());
        o.addProperty("uuid", b.uuid().toString());
        o.addProperty("world", l.getWorld().getName());
        o.addProperty("x", round(l.getX()));
        o.addProperty("y", round(l.getY()));
        o.addProperty("z", round(l.getZ()));
        return o;
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
