package com.uxplima.craftwire.paper.events;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * A readable record of one event: its type, whether it ended up cancelled, the player involved, and the values of
 * its own getters (numbers, text, enums, ids, places, items, entities). Getters that return opaque objects, throw,
 * or belong to Event itself are left out. Getter lists are computed once per event class.
 */
public final class EventSnapshot {
    private EventSnapshot() {}

    private static final int MAX_TEXT = 200;
    private static final Set<String> PLUMBING = Set.of("getHandlers", "getHandlerList", "getEventName", "isAsynchronous", "isCancelled", "callEvent");
    private static final Map<Class<?>, List<Getter>> GETTERS = new ConcurrentHashMap<>();

    private record Getter(String field, Method method) {}

    static JsonObject of(Event e, long time) {
        JsonObject r = new JsonObject();
        r.addProperty("time", time);
        r.addProperty("type", e.getClass().getSimpleName());
        r.addProperty("class", e.getClass().getName());
        if (e instanceof Cancellable c) r.addProperty("cancelled", c.isCancelled());
        if (e.isAsynchronous()) r.addProperty("async", true);
        JsonObject fields = new JsonObject();
        for (Getter g : getters(e.getClass())) {
            Object v;
            try {
                v = g.method().invoke(e);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                continue;
            }
            if (v instanceof Player p && !r.has("player")) r.addProperty("player", p.getName());
            String text = describe(v);
            if (text == null) continue;
            if (v instanceof Number n) fields.addProperty(g.field(), n);
            else if (v instanceof Boolean b) fields.addProperty(g.field(), b);
            else fields.addProperty(g.field(), text);
        }
        r.add("fields", fields);
        return r;
    }

    /** The player an event is about, if any (from its getters), for filtering. */
    public static String playerOf(JsonObject snapshot) {
        return snapshot.has("player") ? snapshot.get("player").getAsString() : null;
    }

    private static List<Getter> getters(Class<?> type) {
        return GETTERS.computeIfAbsent(type, t -> {
            List<Getter> out = new ArrayList<>();
            for (Method m : t.getMethods()) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Event.class
                        || m.getDeclaringClass() == Object.class || PLUMBING.contains(m.getName()) || m.getReturnType() == void.class) {
                    continue;
                }
                String field = fieldName(m.getName());
                if (field == null) continue;
                m.setAccessible(true);
                out.add(new Getter(field, m));
            }
            out.sort((a, b) -> a.field().compareTo(b.field()));
            return List.copyOf(out);
        });
    }

    private static String fieldName(String method) {
        for (String prefix : new String[] {"get", "is", "has"}) {
            if (method.length() > prefix.length() && method.startsWith(prefix) && Character.isUpperCase(method.charAt(prefix.length()))) {
                String rest = method.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return null;
    }

    /** Text for values worth showing; null for anything opaque. */
    static String describe(Object v) {
        return switch (v) {
            case null -> null;
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            case CharSequence s -> cut(s.toString());
            case Character c -> c.toString();
            case Enum<?> en -> en.name();
            case UUID u -> u.toString();
            case Component c -> cut(PlainTextComponentSerializer.plainText().serialize(c));
            case Player p -> p.getName();
            case Entity en -> en.getType().name().toLowerCase(Locale.ROOT) + " " + en.getUniqueId();
            case Block b -> b.getType().name().toLowerCase(Locale.ROOT) + "@" + b.getX() + "," + b.getY() + "," + b.getZ();
            case Location l -> l.getX() + "," + l.getY() + "," + l.getZ() + (l.isWorldLoaded() && l.getWorld() != null ? " " + l.getWorld().getName() : "");
            case World w -> w.getName();
            case ItemStack i -> i.getType().name().toLowerCase(Locale.ROOT) + " x" + i.getAmount();
            case Vector vec -> vec.getX() + "," + vec.getY() + "," + vec.getZ();
            case Collection<?> c -> c.size() <= 10 && c.stream().allMatch(x -> describe(x) != null)
                    ? cut(c.stream().map(EventSnapshot::describe).toList().toString())
                    : c.size() + " items";
            default -> null;
        };
    }

    private static String cut(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…";
    }
}
