package com.uxplima.craftwire.paper.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.build.BuildContext;
import com.uxplima.craftwire.build.BuildError;
import com.uxplima.craftwire.build.Changes;
import com.uxplima.craftwire.build.EditBuffer;
import com.uxplima.craftwire.build.LongIntMap;
import com.uxplima.craftwire.build.Pos;
import com.uxplima.craftwire.build.Project;
import com.uxplima.craftwire.build.ProjectStore;
import com.uxplima.craftwire.build.Transform;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.mc.BuildStates;
import com.uxplima.craftwire.paper.AgentConfig;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.script.BuildScriptRunner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** Build projects: scripts run on two worker threads, one request at a time per project, stored under plugins/Craftwire/builds. */
public final class BuildService {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private final CraftwirePlugin plugin;
    private final ProjectStore store;
    private final BuildScriptRunner runner;
    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<?>> tails = new ConcurrentHashMap<>();
    private final ExecutorService workers;

    public BuildService(CraftwirePlugin plugin) {
        this.plugin = plugin;
        this.store = new ProjectStore(plugin.getDataFolder().toPath().resolve("builds"), BuildStates.INSTANCE);
        this.runner = new BuildScriptRunner(plugin.getClass().getClassLoader());
        AtomicInteger n = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "craftwire-build-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        reload();
    }

    /** Drops projects in memory and loads them from disk again (startup, and the restart IT). */
    public synchronized void reload() {
        projects.clear();
        try {
            for (Project p : store.loadAll()) projects.put(p.name(), p);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not load build projects: " + e);
        }
        store.warnings().forEach(w -> plugin.getLogger().warning(w));
    }

    public void close() {
        workers.shutdownNow();
        runner.close();
    }

    /** Runs work after the previous request on the same project, on a worker thread. */
    private CompletableFuture<JsonElement> queued(String project, java.util.function.Supplier<CompletableFuture<JsonElement>> work) {
        CompletableFuture<JsonElement> mine = new CompletableFuture<>();
        tails.compute(project, (k, prev) -> {
            CompletableFuture<?> before = prev == null ? CompletableFuture.completedFuture(null) : prev.handle((v, t) -> null);
            before.thenComposeAsync(v -> work.get(), workers).whenComplete((v, t) -> {
                if (t != null) mine.completeExceptionally(unwrap(t));
                else mine.complete(v);
            });
            return mine;
        });
        return mine;
    }

    static Throwable unwrap(Throwable t) {
        Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        return c instanceof BuildError b ? new AgentError(b.code(), b.getMessage(), b.hint()) : c;
    }

    private static String checkName(String name, String what) {
        if (!NAME.matcher(name).matches()) throw Args.invalid(what + " names use letters, digits, _ and - (at most 64): " + name);
        return name;
    }

    private static int quarterTurns(JsonObject p) {
        int deg = Args.optInt(p, "rotation").orElse(0);
        if (deg % 90 != 0) throw Args.invalid("rotation must be 0, 90, 180 or 270");
        return Math.floorMod(deg / 90, 4);
    }

    private static String mirror(JsonObject p) {
        String m = Args.optString(p, "mirror").orElse("none");
        return switch (m) {
            case "none" -> "";
            case "x", "z" -> m;
            default -> throw Args.invalid("mirror must be none, x or z");
        };
    }

    private static Transform frame(JsonObject origin, JsonObject p) {
        return Transform.of(Args.integer(origin, "x"), Args.integer(origin, "y"), Args.integer(origin, "z"), quarterTurns(p), mirror(p));
    }

    public CompletableFuture<JsonElement> run(JsonObject p) {
        AgentConfig config = plugin.agentConfig();
        config.require(config.allowBuild(), "allow-build");
        String name = checkName(Args.string(p, "project"), "Project");
        return queued(name, () -> {
            long started = System.currentTimeMillis();
            Project project = projects.get(name);
            if (project == null) {
                if (!p.has("origin")) {
                    throw new AgentError("BAD_ARGUMENT", "Project " + name + " is new, so it needs an origin {x,y,z}", "Pass origin on the first build of a project.");
                }
                project = new Project(name, Args.world(p).getName(), frame(Args.object(p, "origin"), p));
            } else if (p.has("origin") && !frame(Args.object(p, "origin"), p).equals(project.frame())) {
                throw new AgentError("BAD_ARGUMENT", "Project " + name + " already sits at another origin/rotation",
                        "Use build_manage {action:'move'} to move or turn it, or leave origin out.");
            }
            Project pr = project;
            World world = world(pr);
            String code = Args.string(p, "code");
            String file = Args.optString(p, "file").orElse(null);
            long seed = Args.optLong(p, "seed").orElse(0L);
            long timeout = Math.min(300_000, Args.optLong(p, "timeoutMs").orElse(60_000L));
            BuildContext ctx = new BuildContext(BuildStates.INSTANCE, new PaperWorldReader(plugin.sync(), world), pr.frame(), config.maxBuildVolume(), seed);
            BuildScriptRunner.Result result = runner.run(code, file, Args.optString(p, "part").orElse(null), "main", ctx, timeout);
            boolean dry = Args.bool(p, "dryRun", false);
            JsonObject out = new JsonObject();
            out.addProperty("project", name);
            JsonArray parts = new JsonArray();
            List<String> warnings = new ArrayList<>();
            int[] totals = new int[3];   // changed, removed, blocks
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (BuildScriptRunner.Part part : result.parts()) {
                chain = chain.thenCompose(v -> {
                    Changes changes = pr.stage(part.name(), part.buffer().blocks());
                    JsonObject pj = new JsonObject();
                    pj.addProperty("part", part.name());
                    pj.addProperty("blocks", part.buffer().size());
                    totals[2] += part.buffer().size();
                    if (part.buffer().outOfRange() > 0) warnings.add(part.buffer().outOfRange() + " blocks of " + part.name() + " were outside the world and dropped");
                    if (dry) {
                        pj.addProperty("wouldChange", changes.size());
                        parts.add(pj);
                        return CompletableFuture.completedFuture(null);
                    }
                    return apply(world, pr, changes).thenAccept(o -> {
                        pr.commit(part.name(), part.buffer().blocks(), new com.uxplima.craftwire.build.Project.Source(code, file), true);
                        projects.put(name, pr);
                        pj.addProperty("changed", o.changed());
                        pj.addProperty("removed", changes.removed());
                        totals[0] += o.changed();
                        totals[1] += changes.removed();
                        if (o.outsideHeight() > 0) warnings.add(o.outsideHeight() + " blocks of " + part.name() + " were outside the build height and skipped");
                        if (o.unsupported() > 0) warnings.add(o.unsupported() + " blocks need support they do not have (e.g. " + String.join("; ", o.unsupportedAt()) + ")");
                        parts.add(pj);
                    });
                });
            }
            return chain.thenApply(v -> {
                if (!dry) save(pr);
                out.add("parts", parts);
                out.addProperty("changed", totals[0]);
                out.addProperty("removed", totals[1]);
                out.addProperty("blocks", totals[2]);
                out.add("bbox", bbox(result));
                out.add("palette", palette(result));
                JsonArray w = new JsonArray();
                warnings.forEach(w::add);
                if (overlaps(pr, result)) w.add("parts overlap; the part run last covers the others");
                out.add("warnings", w);
                if (!result.output().isEmpty()) out.addProperty("output", result.output());
                out.addProperty("ms", System.currentTimeMillis() - started);
                return (JsonElement) out;
            });
        });
    }

    private void save(Project p) {
        try {
            store.save(p);
            store.enforceLimit(plugin.agentConfig().buildJournalLimitMb() * 1024L * 1024L, projects);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save build project " + p.name() + ": " + e);
        }
    }

    /**
     * Applies on the owning threads, then continues on a build worker: scripts, diffs and disk writes never run on a
     * server thread (a script read would wait for that very thread).
     */
    private CompletableFuture<Applier.Outcome> apply(World world, Project pr, Changes changes) {
        return Applier.apply(plugin.sync(), world, pr, changes).thenApplyAsync(o -> o, workers);
    }

    private static World world(Project p) {
        World w = Bukkit.getWorld(p.world());
        if (w == null) throw new AgentError("WORLD_NOT_FOUND", "World " + p.world() + " is not loaded", "Load that world, then retry.");
        return w;
    }

    private static JsonElement bbox(BuildScriptRunner.Result r) {
        int[] b = null;
        for (BuildScriptRunner.Part part : r.parts()) {
            int[] pb = part.buffer().bbox();
            if (pb == null) continue;
            if (b == null) b = pb.clone();
            else for (int i = 0; i < 3; i++) {
                b[i] = Math.min(b[i], pb[i]);
                b[i + 3] = Math.max(b[i + 3], pb[i + 3]);
            }
        }
        if (b == null) return com.google.gson.JsonNull.INSTANCE;
        JsonObject o = new JsonObject();
        o.add("min", xyz(b[0], b[1], b[2]));
        o.add("max", xyz(b[3], b[4], b[5]));
        return o;
    }

    static JsonObject xyz(int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    private static JsonArray palette(BuildScriptRunner.Result r) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (BuildScriptRunner.Part part : r.parts()) part.buffer().blocks().forEach((pos, s) -> counts.merge(s, 1, Integer::sum));
        JsonArray a = new JsonArray();
        counts.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(10).forEach(e -> {
            JsonArray row = new JsonArray();
            row.add(BuildStates.INSTANCE.name(e.getKey()));
            row.add(e.getValue());
            a.add(row);
        });
        return a;
    }

    private static boolean overlaps(Project p, BuildScriptRunner.Result r) {
        List<String> names = p.parts();
        if (names.size() < 2) return false;
        for (BuildScriptRunner.Part part : r.parts()) {
            boolean[] hit = {false};
            for (String other : names) {
                if (other.equals(part.name())) continue;
                LongIntMap o = p.part(other);
                if (o == null) continue;
                part.buffer().blocks().forEach((pos, s) -> { if (!hit[0] && o.containsKey(pos)) hit[0] = true; });
                if (hit[0]) return true;
            }
        }
        return false;
    }

    public CompletableFuture<JsonElement> manage(JsonObject p) {
        AgentConfig config = plugin.agentConfig();
        config.require(config.allowBuild(), "allow-build");
        String action = Args.string(p, "action");
        if (action.equals("list")) return CompletableFuture.completedFuture(list());
        String name = checkName(Args.string(p, "project"), "Project");
        return queued(name, () -> {
            Project pr = projects.get(name);
            if (pr == null) throw new AgentError("PROJECT_NOT_FOUND", "No build project named " + name, "build_manage {action:'list'} shows the projects.");
            World world = world(pr);
            return switch (action) {
                case "undo" -> undo(pr, world, Math.max(1, Args.optInt(p, "steps").orElse(1)));
                case "remove" -> {
                    String part = Args.string(p, "part");
                    if (pr.part(part) == null) throw new AgentError("BAD_ARGUMENT", "Project " + name + " has no part " + part, "Parts: " + pr.parts());
                    yield apply(world, pr, pr.stage(part, null)).thenApply(o -> {
                        pr.commit(part, null, null, true);
                        save(pr);
                        return summary(pr, o.changed());
                    });
                }
                case "clear" -> apply(world, pr, pr.clearAll()).thenApply(o -> {
                    forget(pr);
                    JsonObject r = new JsonObject();
                    r.addProperty("project", name);
                    r.addProperty("restored", o.changed());
                    return (JsonElement) r;
                });
                case "move" -> move(pr, world, p);
                case "commit" -> {
                    forget(pr);
                    JsonObject r = new JsonObject();
                    r.addProperty("project", name);
                    r.addProperty("committed", true);
                    yield CompletableFuture.completedFuture((JsonElement) r);
                }
                default -> throw Args.invalid("action must be list, undo, remove, clear, move or commit");
            };
        });
    }

    private void forget(Project pr) {
        projects.remove(pr.name());
        pr.reset();
        try {
            store.delete(pr.name());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not delete build project " + pr.name() + ": " + e);
        }
    }

    private CompletableFuture<JsonElement> undo(Project pr, World world, int steps) {
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (int i = 0; i < steps; i++) {
            chain = chain.thenCompose(n -> {
                Project.History h = pr.popHistory();
                if (h == null) return CompletableFuture.completedFuture(n);
                return apply(world, pr, pr.stage(h.part(), h.previous())).thenApply(o -> {
                    pr.commit(h.part(), h.previous(), h.previousSource(), false);
                    return n + o.changed();
                });
            });
        }
        return chain.thenApply(changed -> {
            save(pr);
            return summary(pr, changed);
        });
    }

    private CompletableFuture<JsonElement> move(Project pr, World world, JsonObject p) {
        Transform old = pr.frame();
        JsonObject origin = p.has("origin") ? Args.object(p, "origin") : BuildService.xyz(old.ox(), old.oy(), old.oz());
        Transform next = frame(origin, p);
        Map<String, Project.Source> sources = new LinkedHashMap<>();
        for (String part : pr.parts()) sources.put(part, pr.source(part));
        return apply(world, pr, pr.clearAll()).thenCompose(o -> {
            pr.reset();
            pr.setFrame(next);
            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
            for (Map.Entry<String, Project.Source> e : sources.entrySet()) {
                chain = chain.thenCompose(n -> {
                    Project.Source s = e.getValue();
                    BuildContext ctx = new BuildContext(BuildStates.INSTANCE, new PaperWorldReader(plugin.sync(), world), next, plugin.agentConfig().maxBuildVolume(), 0);
                    BuildScriptRunner.Result r = runner.run(s.code(), s.file(), e.getKey().equals("main") && !s.code().contains("part(") ? null : e.getKey(), e.getKey(), ctx, 300_000);
                    EditBuffer buf = r.parts().get(0).buffer();
                    return apply(world, pr, pr.stage(e.getKey(), buf.blocks())).thenApply(o2 -> {
                        pr.commit(e.getKey(), buf.blocks(), s, false);
                        return n + o2.changed();
                    });
                });
            }
            return chain;
        }).thenApply(changed -> {
            save(pr);
            return summary(pr, changed);
        });
    }

    private JsonElement summary(Project pr, int changed) {
        JsonObject r = new JsonObject();
        r.addProperty("project", pr.name());
        r.addProperty("changed", changed);
        JsonArray parts = new JsonArray();
        pr.parts().forEach(parts::add);
        r.add("parts", parts);
        r.addProperty("undoSteps", pr.history().size());
        return r;
    }

    private JsonElement list() {
        JsonArray a = new JsonArray();
        for (Project p : projects.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("project", p.name());
            o.addProperty("world", p.world());
            Transform f = p.frame();
            o.add("origin", xyz(f.ox(), f.oy(), f.oz()));
            o.addProperty("rotation", f.rot() * 90);
            JsonArray parts = new JsonArray();
            for (String part : p.parts()) {
                JsonObject pj = new JsonObject();
                pj.addProperty("part", part);
                pj.addProperty("blocks", p.part(part).size());
                parts.add(pj);
            }
            o.add("parts", parts);
            o.addProperty("undoSteps", p.history().size());
            a.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("projects", a);
        return r;
    }
}
