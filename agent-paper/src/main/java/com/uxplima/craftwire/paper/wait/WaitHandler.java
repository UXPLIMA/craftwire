package com.uxplima.craftwire.paper.wait;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.events.EventSnapshot;
import com.uxplima.craftwire.paper.events.EventTap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

/**
 * wait: blocks (without blocking any thread) until a server-side condition holds. Tick conditions are checked on the
 * server thread every tick, starting with the tick after the call; `event` listens for events fired after the call.
 * Resolves with {matched, condition, elapsedMs, value} or, on timeout, {matched:false, …, last}.
 */
public final class WaitHandler {
    private WaitHandler() {}

    public static CompletableFuture<JsonElement> handle(JsonObject p, CraftwirePlugin plugin) {
        String condition = Args.string(p, "condition");
        long timeoutMs = Math.clamp(Args.optLong(p, "timeoutMs").orElse(30_000L), 10L, 300_000L);
        Wait wait = new Wait(condition, timeoutMs, System::currentTimeMillis);
        long startedAt = System.currentTimeMillis();
        CompletableFuture<Void> started = condition.equals("event")
                ? plugin.sync().global(() -> { listen(p, plugin, wait); return null; })
                : plugin.sync().global(() -> Conditions.probe(condition, p, plugin, startedAt)).thenAccept(probe -> tick(plugin, wait, probe));
        started.whenComplete((v, err) -> {
            if (err != null) wait.fail(err);
            else CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS).execute(wait::timeOut);
        });
        return wait.result();
    }

    private static void tick(CraftwirePlugin plugin, Wait wait, Conditions.Probe probe) {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (wait.done()) {
                task.cancel();
                return;
            }
            try {
                Conditions.Check c = probe.check();
                if (wait.observe(c.met(), c.value())) task.cancel();
            } catch (RuntimeException e) {
                wait.fail(e);
                task.cancel();
            }
        }, 1, 1);
    }

    /** `type` (simple or full class name), optionally the `player` it is about and a `pattern` its JSON must match. */
    private static void listen(JsonObject p, CraftwirePlugin plugin, Wait wait) {
        EventTap tap = plugin.events();
        if (tap == null) {
            throw new AgentError("PERMISSION_DISABLED", "record-events is disabled in plugins/Craftwire/config.yml",
                    "Ask the server owner to set record-events: true and restart the server.");
        }
        String player = Args.optString(p, "player").orElse(null);
        Pattern pattern = Args.optString(p, "pattern").map(Conditions::regex).orElse(null);
        Runnable stop = tap.subscribe(Args.string(p, "type"), snapshot -> {
            boolean met = (player == null || player.equalsIgnoreCase(EventSnapshot.playerOf(snapshot)))
                    && (pattern == null || pattern.matcher(snapshot.toString()).find());
            wait.observe(met, snapshot);
        });
        wait.result().whenComplete((r, e) -> stop.run());
    }
}
