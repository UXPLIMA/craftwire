package com.uxplima.craftwire.paper.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

/**
 * Feeds every Bukkit event into an EventRecorder: one MONITOR listener (sees the outcome, including cancelled
 * events) on every HandlerList. A HandlerList only exists once its event class is initialised, so the event classes
 * of the API and of every plugin are initialised up front: even a plugin's own event is seen the first time it fires.
 * Events Paper only fires while someone listens (entity movement, physics, ticks…) are left alone unless watched,
 * because listening to them would slow the server down.
 */
public final class EventTap implements Listener {
    /** Fired only while listened to, or many times per tick: recorded only on request (`watch`). */
    static final Set<String> ON_REQUEST = Set.of(
            "io.papermc.paper.event.entity.EntityMoveEvent",
            "io.papermc.paper.event.entity.EntityInsideBlockEvent",
            "io.papermc.paper.event.packet.PlayerChunkLoadEvent",
            "io.papermc.paper.event.packet.PlayerChunkUnloadEvent",
            "com.destroystokyo.paper.event.server.ServerTickStartEvent",
            "com.destroystokyo.paper.event.server.ServerTickEndEvent",
            "com.destroystokyo.paper.event.entity.EntityPathfindEvent",
            "org.bukkit.event.block.BlockPhysicsEvent",
            "org.bukkit.event.block.BlockRedstoneEvent",
            "org.bukkit.event.block.BlockFromToEvent",
            "org.bukkit.event.world.GenericGameEvent",
            "org.bukkit.event.vehicle.VehicleUpdateEvent",
            "org.bukkit.event.vehicle.VehicleMoveEvent",
            "org.bukkit.event.entity.EntityAirChangeEvent",
            "org.bukkit.event.inventory.InventoryMoveItemEvent");
    private static final List<String> API_PACKAGES = List.of("org/bukkit/event/", "io/papermc/paper/event/", "com/destroystokyo/paper/event/");

    private final Plugin plugin;
    private final EventRecorder recorder;
    private final RegisteredListener listener;
    private final Set<HandlerList> attached = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Set<HandlerList> onRequest = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    /** Event classes found while preparing, by simple name (for `listeners` and `watch`). */
    private final Map<String, List<Class<? extends Event>>> known = new ConcurrentHashMap<>();
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    private record Subscription(Class<? extends Event> type, HandlerList handlers, Consumer<JsonObject> sink) {}

    public EventTap(Plugin plugin) {
        this.plugin = plugin;
        this.recorder = new EventRecorder(5000, 20, System::currentTimeMillis);
        this.listener = new RegisteredListener(this, (l, e) -> onEvent(e), EventPriority.MONITOR, plugin, false);
    }

    public void start() {
        for (String name : ON_REQUEST) {
            HandlerList h = handlerList(name, Event.class.getClassLoader());
            if (h != null) onRequest.add(h);
        }
        prepare(Event.class, Event.class.getClassLoader(), API_PACKAGES, false);
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) preparePlugin(p);
        attachAll();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Lists created later (a class nothing initialised yet) are picked up within a second.
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> attachAll(), 20, 20);
    }

    public void stop() {
        HandlerList.unregisterAll((Listener) this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent e) {
        preparePlugin(e.getPlugin());
        attachAll();
    }

    public JsonObject summary(long since) {
        return recorder.summary(since);
    }

    public JsonObject query(String type, String player, long since, int limit, boolean cancelledOnly) {
        return recorder.query(new EventRecorder.Query(type, player, since, limit, cancelledOnly));
    }

    /** Who listens to an event type: plugin, priority, whether cancelled events reach it, and the listener class. */
    public JsonObject listeners(String type) {
        Class<? extends Event> c = resolve(type);
        HandlerList h = handlerList(c.getName(), c.getClassLoader());
        JsonArray out = new JsonArray();
        if (h != null) {
            for (RegisteredListener r : h.getRegisteredListeners()) {
                if (r.getListener() == this) continue;
                JsonObject o = new JsonObject();
                o.addProperty("plugin", r.getPlugin().getName());
                o.addProperty("priority", r.getPriority().name());
                o.addProperty("ignoreCancelled", r.isIgnoringCancelled());
                o.addProperty("listener", r.getListener().getClass().getName());
                out.add(o);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("event", c.getName());
        o.addProperty("recorded", h != null && attached.contains(h));
        o.add("listeners", out);
        return o;
    }

    /** Also records event types that are skipped by default (see ON_REQUEST). */
    public synchronized JsonObject watch(List<String> types) {
        JsonArray watched = new JsonArray();
        for (String t : types) {
            Class<? extends Event> c = resolve(t);
            HandlerList h = handlerList(c.getName(), c.getClassLoader());
            if (h == null) throw new AgentError("INVALID_PARAMS", c.getName() + " has no handler list", "Watch a concrete event type.");
            onRequest.remove(h);
            if (attached.add(h)) h.register(listener);
            watched.add(c.getName());
        }
        JsonObject o = new JsonObject();
        o.add("watching", watched);
        return o;
    }

    /**
     * Hands a snapshot of every `type` event fired from now on to `sink` (on the thread that fired it). A busy type
     * that is not recorded by default is tapped only while subscribed. Run the returned handle to unsubscribe.
     */
    public synchronized Runnable subscribe(String type, Consumer<JsonObject> sink) {
        Class<? extends Event> c = resolve(type);
        HandlerList h = handlerList(c.getName(), c.getClassLoader());
        if (h == null) throw new AgentError("INVALID_PARAMS", c.getName() + " has no handler list", "Wait for a concrete event type.");
        Subscription s = new Subscription(c, h, sink);
        subscriptions.add(s);
        if (onRequest.contains(h) && attached.add(h)) h.register(listener);
        return () -> unsubscribe(s);
    }

    private synchronized void unsubscribe(Subscription s) {
        subscriptions.remove(s);
        HandlerList h = s.handlers();
        // A busy type (not watched meanwhile) that no other subscription needs: stop tapping it.
        if (onRequest.contains(h) && subscriptions.stream().noneMatch(o -> o.handlers() == h) && attached.remove(h)) h.unregister(listener);
    }

    private void onEvent(Event e) {
        recorder.record(e);
        if (subscriptions.isEmpty()) return;
        JsonObject snapshot = null;
        for (Subscription s : subscriptions) {
            if (!s.type().isInstance(e)) continue;
            if (snapshot == null) snapshot = EventSnapshot.of(e, System.currentTimeMillis());
            s.sink().accept(snapshot);
        }
    }

    private void attachAll() {
        List<HandlerList> lists;
        ArrayList<HandlerList> all = HandlerList.getHandlerLists();
        synchronized (all) {
            lists = new ArrayList<>(all);
        }
        for (HandlerList h : lists) {
            if (!onRequest.contains(h) && attached.add(h)) h.register(listener);
        }
    }

    private void preparePlugin(Plugin p) {
        if (p == plugin) return;
        prepare(p.getClass(), p.getClass().getClassLoader(), List.of(""), true);
    }

    /**
     * Initialises the event classes in the jar that holds `anchor` (under `packages`), which creates their
     * HandlerLists. With `cheapFilter`, only classes whose bytes mention getHandlerList are loaded at all.
     */
    private void prepare(Class<?> anchor, ClassLoader loader, List<String> packages, boolean cheapFilter) {
        Path jar = jarOf(anchor);
        if (jar == null) return;
        try (JarFile f = new JarFile(jar.toFile())) {
            for (JarEntry e : Collections.list(f.entries())) {
                String name = e.getName();
                if (!name.endsWith(".class") || name.equals("module-info.class") || packages.stream().noneMatch(name::startsWith)) continue;
                if (cheapFilter && !mentionsHandlerList(f, e)) continue;
                String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                initEvent(className, loader);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.FINE, "Could not scan " + jar + " for events", ex);
        }
    }

    private static boolean mentionsHandlerList(JarFile f, JarEntry e) throws IOException {
        try (InputStream in = f.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).contains("getHandlerList");
        }
    }

    private void initEvent(String className, ClassLoader loader) {
        try {
            Class<?> c = Class.forName(className, false, loader);
            if (!Event.class.isAssignableFrom(c) || Modifier.isAbstract(c.getModifiers()) || c.isInterface()) return;
            @SuppressWarnings("unchecked") Class<? extends Event> ev = (Class<? extends Event>) c;
            known.computeIfAbsent(c.getSimpleName().toLowerCase(Locale.ROOT), k -> Collections.synchronizedList(new ArrayList<>())).add(ev);
            Method m = c.getMethod("getHandlerList");
            if (Modifier.isStatic(m.getModifiers())) m.invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // Optional dependencies missing, or not an event with a handler list: nothing to tap.
        }
    }

    private Class<? extends Event> resolve(String type) {
        List<Class<? extends Event>> bySimple = known.get(type.toLowerCase(Locale.ROOT));
        if (bySimple != null && !bySimple.isEmpty()) return bySimple.get(0);
        for (List<Class<? extends Event>> list : known.values()) {
            for (Class<? extends Event> c : list) if (c.getName().equalsIgnoreCase(type)) return c;
        }
        throw new AgentError("EVENT_NOT_FOUND", "No event type named " + type,
                "Use the simple name (PlayerInteractEvent) or the full class name; events summary lists the types that fired.");
    }

    private static HandlerList handlerList(String className, ClassLoader loader) {
        try {
            Method m = Class.forName(className, true, loader).getMethod("getHandlerList");
            return (HandlerList) m.invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static Path jarOf(Class<?> c) {
        try {
            CodeSource src = c.getProtectionDomain().getCodeSource();
            if (src == null) return null;
            Path p = Path.of(src.getLocation().toURI());
            return Files.isRegularFile(p) ? p : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
