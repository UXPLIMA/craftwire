package com.uxplima.craftwire.paper.wait;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.bot.Bot;
import com.uxplima.craftwire.paper.bot.BotInbox;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The server-side wait_for conditions that are checked every tick. Built and checked on the server thread. */
final class Conditions {
    private Conditions() {}

    /** What a condition looks like now. */
    record Check(boolean met, JsonElement value) {}

    @FunctionalInterface
    interface Probe {
        Check check();
    }

    static Probe probe(String condition, JsonObject p, CraftwirePlugin plugin, long startedAt) {
        return switch (condition) {
            case "block" -> block(p);
            case "player_near" -> playerNear(p);
            case "inventory" -> inventory(p);
            case "message" -> message(p, plugin, startedAt);
            case "expr" -> expr(p, plugin);
            default -> throw Args.invalid("Unknown server condition " + condition);
        };
    }

    /** `is` / `isNot`: a block id or state ("oak_door[open=true]"); properties left out match any value. */
    private static Probe block(JsonObject p) {
        World world = Args.world(p);
        int x = (int) Math.floor(Args.number(p, "x"));
        int y = (int) Math.floor(Args.number(p, "y"));
        int z = (int) Math.floor(Args.number(p, "z"));
        Optional<String> is = Args.optString(p, "is");
        Optional<String> isNot = Args.optString(p, "isNot");
        if (is.isPresent() == isNot.isPresent()) throw Args.invalid("block needs exactly one of `is` or `isNot`");
        BlockData pattern = blockData(is.orElseGet(isNot::get));
        boolean want = is.isPresent();
        return () -> {
            BlockData actual = world.getBlockAt(x, y, z).getBlockData();
            return new Check(actual.matches(pattern) == want, new JsonPrimitive(actual.getAsString()));   // the argument's given properties are what must match
        };
    }

    private static BlockData blockData(String s) {
        try {
            return Bukkit.createBlockData(s.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AgentError("INVALID_PARAMS", "Not a block: " + s, "Use a block id such as stone or oak_door[open=true].");
        }
    }

    private static Probe playerNear(JsonObject p) {
        String name = Args.string(p, "player");
        World world = Args.world(p);
        Location target = new Location(world, Args.number(p, "x"), Args.number(p, "y"), Args.number(p, "z"));
        double radius = p.has("radius") ? Args.number(p, "radius") : 1.5;
        return () -> {
            Player pl = Bukkit.getPlayerExact(name);
            if (pl == null) return new Check(false, new JsonPrimitive("offline"));
            Location at = pl.getLocation();
            JsonObject v = new JsonObject();
            v.addProperty("x", round(at.getX()));
            v.addProperty("y", round(at.getY()));
            v.addProperty("z", round(at.getZ()));
            v.addProperty("world", at.getWorld().getName());
            if (!at.getWorld().equals(world)) return new Check(false, v);
            double d = at.distance(target);
            v.addProperty("distance", round(d));
            return new Check(d <= radius, v);
        };
    }

    /** `count`: at least that many (default 1); `atMost`: no more than that many (0 = none left). */
    private static Probe inventory(JsonObject p) {
        String name = Args.string(p, "player");
        String item = Args.string(p, "item");
        Material material = Material.matchMaterial(item);
        if (material == null || !material.isItem()) throw new AgentError("INVALID_PARAMS", "Not an item: " + item, "Use an item id such as diamond.");
        Optional<Integer> atMost = Args.optInt(p, "atMost");
        int atLeast = Args.optInt(p, "count").orElse(1);
        return () -> {
            Player pl = Bukkit.getPlayerExact(name);
            if (pl == null) return new Check(false, new JsonPrimitive("offline"));
            int n = 0;
            for (ItemStack s : pl.getInventory().getContents()) if (s != null && s.getType() == material) n += s.getAmount();
            boolean met = atMost.isPresent() ? n <= atMost.get() : n >= atLeast;
            return new Check(met, new JsonPrimitive(n));
        };
    }

    /** A message a bot receives after the call (chat, system line or action bar) matching `pattern`. */
    private static Probe message(JsonObject p, CraftwirePlugin plugin, long startedAt) {
        Bot bot = plugin.bots().get(Args.string(p, "player"));
        Pattern pattern = regex(Args.string(p, "pattern"));
        return () -> {
            List<BotInbox.Message> got = bot.inbox().since(startedAt, 100);
            for (BotInbox.Message m : got) {
                if (pattern.matcher(m.text()).find()) return new Check(true, message(m));
            }
            return new Check(false, got.isEmpty() ? JsonNull.INSTANCE : message(got.getLast()));
        };
    }

    private static JsonObject message(BotInbox.Message m) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", m.kind());
        o.addProperty("text", m.text());
        if (m.sender() != null) o.addProperty("sender", m.sender());
        return o;
    }

    /** A server_eval script whose result must become truthy. */
    private static Probe expr(JsonObject p, CraftwirePlugin plugin) {
        plugin.agentConfig().require(plugin.agentConfig().allowEval(), "allow-eval");
        String js = Args.string(p, "js");
        return () -> {
            JsonElement v = plugin.scripts().eval(js, 1000).get("result");
            return new Check(truthy(v), v);
        };
    }

    /** JavaScript truthiness of a script result. */
    static boolean truthy(JsonElement v) {
        if (v == null || v.isJsonNull()) return false;
        if (!v.isJsonPrimitive()) return true;
        JsonPrimitive p = v.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsDouble() != 0;
        String s = p.getAsString();
        return !s.isEmpty() && !s.equals("NaN");
    }

    static Pattern regex(String pattern) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new AgentError("INVALID_PARAMS", "Invalid regex: " + e.getDescription(), "Escape special characters such as ( [ . * with a backslash.");
        }
    }

    private static double round(double d) {
        return Math.round(d * 100) / 100.0;
    }
}
