package com.uxplima.craftwire.paper.handlers;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.uxplima.craftwire.core.profile.Frame;
import com.uxplima.craftwire.core.profile.Owner;
import com.uxplima.craftwire.core.profile.OwnerIndex;
import com.uxplima.craftwire.core.profile.ProfileTools;
import com.uxplima.craftwire.core.profile.Profiler;
import com.uxplima.craftwire.paper.Sync;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/** profile and trace on a Paper server: the main thread (Folia: every region thread), owners from plugin jars, tick times from Paper's events. */
public final class PaperProfiling implements ProfileTools.Platform {
    private final Plugin self;
    private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> events = new ConcurrentHashMap<>();
    private volatile OwnerIndex index = OwnerIndex.builder().build();

    public PaperProfiling(Plugin self) {
        this.self = self;
    }

    /** Folia has no main thread: every region (and the global region) ticks on one of its scheduler threads. */
    private static final String FOLIA_THREADS = "Folia Region Scheduler Thread";

    @Override
    public String gameThread() {
        return Sync.folia() ? FOLIA_THREADS + " #*" : "Server thread";
    }

    @Override
    public Predicate<String> gameThreads() {
        return Sync.folia() ? name -> name.startsWith(FOLIA_THREADS) : "Server thread"::equals;
    }

    @Override
    public OwnerIndex owners() {
        OwnerIndex.Builder b = OwnerIndex.builder();
        plugins.clear();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            Path jar = jarOf(p);
            if (jar != null) b.add(p == self ? Owner.CRAFTWIRE : new Owner(p.getName(), Owner.PLUGIN), jar);
            plugins.put(p.getName(), p);
        }
        index = b.build();
        return index;
    }

    /** A listener method's event, from its parameter type, loaded through the plugin's own class loader. */
    @Override
    public String eventOf(Frame frame) {
        return events.computeIfAbsent(frame.qualified(), k -> {
            Owner owner = index.owner(frame.className(), frame.method());
            Plugin plugin = owner.isAddon() ? plugins.get(owner.name()) : null;
            if (plugin == null) return Optional.empty();
            try {
                Class<?> c = Class.forName(frame.className(), false, plugin.getClass().getClassLoader());
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(frame.method()) && m.getParameterCount() == 1
                            && Event.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        return Optional.of(m.getParameterTypes()[0].getSimpleName());
                    }
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // not a class we can look at: no event name
            }
            return Optional.empty();
        }).orElse(null);
    }

    private static Path jarOf(Plugin p) {
        try {
            CodeSource source = p.getClass().getProtectionDomain().getCodeSource();
            return source == null ? null : Path.of(source.getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ProfileTools.Feed feed(Profiler.Session session) {
        Listener ticks = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onTickEnd(ServerTickEndEvent e) {
                session.tick(e.getTickNumber(), Profiler.now(), (long) (e.getTickDuration() * 1_000_000L));
            }
        };
        Bukkit.getPluginManager().registerEvents(ticks, self);
        return report -> HandlerList.unregisterAll(ticks);
    }
}
