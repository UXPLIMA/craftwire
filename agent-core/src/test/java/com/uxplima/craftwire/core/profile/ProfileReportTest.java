package com.uxplima.craftwire.core.profile;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileReportTest {
    private static final Owner SHOP = new Owner("MyShop", Owner.PLUGIN);
    private static final Owner OTHER = new Owner("Other", Owner.PLUGIN);
    private final OwnerIndex owners = OwnerIndex.builder()
            .addClasses(SHOP, List.of("com.shop.ShopListener", "com.shop.Prices"))
            .addClasses(OTHER, List.of("com.other.Task"))
            .build();

    /** Frames from the innermost (top) to the outermost. */
    private static List<Frame> stack(String... frames) {
        List<Frame> out = new ArrayList<>();
        for (String f : frames) {
            String[] p = f.split(" ", 2);
            int dot = p[0].lastIndexOf('.');
            out.add(new Frame(p[0].substring(0, dot), p[0].substring(dot + 1), p.length > 1 ? p[1] : "()V", 1));
        }
        return out;
    }

    private static final List<Frame> SERVER_LOOP = stack(
            "org.bukkit.craftbukkit.event.CraftEventFactory.callEvent",
            "net.minecraft.server.network.ServerGamePacketListenerImpl.handleMovePlayer",
            "net.minecraft.server.MinecraftServer.tickServer",
            "net.minecraft.server.MinecraftServer.runServer");

    private static List<Frame> concat(List<Frame> a, List<Frame> b) {
        List<Frame> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static JsonObject find(JsonArray a, String key, String value) {
        for (JsonElement e : a) if (e.getAsJsonObject().get(key).getAsString().equals(value)) return e.getAsJsonObject();
        throw new AssertionError(value + " not in " + a);
    }

    @Test
    void attributesEachSampleToTheInnermostPluginFrameEvenInsideMinecraftCode() {
        ProfileReport r = new ProfileReport(owners);
        // MyShop's listener calls into Minecraft (the world lookup), which is the top frame: still MyShop's time.
        List<Frame> listenerCall = concat(stack(
                "net.minecraft.world.level.Level.getBlockState",
                "com.shop.Prices.lookup",
                "com.shop.ShopListener.onMove (Lorg/bukkit/event/player/PlayerMoveEvent;)V"), SERVER_LOOP);
        for (int i = 0; i < 6; i++) r.add(listenerCall, i * 10_000_000L);
        for (int i = 0; i < 3; i++) r.add(concat(stack("com.other.Task.run", "org.bukkit.craftbukkit.scheduler.CraftTask.run"), SERVER_LOOP), 0);
        r.add(stack("net.minecraft.server.MinecraftServer.waitUntilNextTick", "net.minecraft.server.MinecraftServer.runServer"), 0);
        JsonObject o = r.json(5);

        assertEquals(10, o.get("samples").getAsInt());
        JsonArray byOwner = o.getAsJsonArray("owners");
        assertEquals("MyShop", byOwner.get(0).getAsJsonObject().get("owner").getAsString());
        assertEquals(60.0, find(byOwner, "owner", "MyShop").get("percent").getAsDouble());
        assertEquals("plugin", find(byOwner, "owner", "MyShop").get("kind").getAsString());
        assertEquals(30.0, find(byOwner, "owner", "Other").get("percent").getAsDouble());
        assertEquals(10.0, find(byOwner, "owner", "minecraft").get("percent").getAsDouble());
    }

    @Test
    void entryPointsNameTheListenerEventAndTheSchedulerTask() {
        ProfileReport r = new ProfileReport(owners);
        r.add(concat(stack("com.shop.Prices.lookup", "com.shop.ShopListener.onMove (Lorg/bukkit/event/player/PlayerMoveEvent;)V"), SERVER_LOOP), 0);
        r.add(concat(stack("com.other.Task.run", "org.bukkit.craftbukkit.scheduler.CraftTask.run"), SERVER_LOOP), 0);
        JsonArray entries = r.json(5).getAsJsonArray("entryPoints");

        JsonObject listener = find(entries, "method", "com.shop.ShopListener.onMove");
        assertEquals("MyShop", listener.get("owner").getAsString());
        assertEquals("PlayerMoveEvent", listener.get("event").getAsString());
        assertEquals("org.bukkit.craftbukkit.event.CraftEventFactory.callEvent", listener.get("calledFrom").getAsString());
        JsonObject task = find(entries, "method", "com.other.Task.run");
        assertTrue(task.get("task").getAsBoolean());
        assertEquals(50.0, task.get("percent").getAsDouble());
    }

    @Test
    void hotMethodsAreSelfTimeWithTheirOwner() {
        ProfileReport r = new ProfileReport(owners);
        for (int i = 0; i < 3; i++) r.add(concat(stack("net.minecraft.world.level.Level.getBlockState", "com.shop.Prices.lookup"), SERVER_LOOP), 0);
        r.add(concat(stack("com.shop.Prices.lookup"), SERVER_LOOP), 0);
        JsonArray hot = r.json(5).getAsJsonArray("hotMethods");
        JsonObject first = hot.get(0).getAsJsonObject();
        assertEquals("net.minecraft.world.level.Level.getBlockState", first.get("method").getAsString());
        assertEquals("minecraft", first.get("owner").getAsString());
        assertEquals(75.0, first.get("percent").getAsDouble());
        assertEquals("MyShop", find(hot, "method", "com.shop.Prices.lookup").get("owner").getAsString());
    }

    @Test
    void ticksGiveMsptAndTheOwnersSampledDuringTheSlowestTicks() {
        ProfileReport r = new ProfileReport(owners);
        // Tick 1: 0-40 ms, MyShop. Tick 2: 50-150 ms (slow), Other.
        for (long t = 0; t < 40; t += 10) r.add(concat(stack("com.shop.Prices.lookup"), SERVER_LOOP), t * 1_000_000L);
        for (long t = 60; t < 150; t += 10) r.add(concat(stack("com.other.Task.run"), SERVER_LOOP), t * 1_000_000L);
        r.tick(1, 40_000_000L, 40_000_000L);
        r.tick(2, 150_000_000L, 100_000_000L);
        JsonObject ticks = r.json(5).getAsJsonObject("ticks");
        assertEquals(2, ticks.get("count").getAsInt());
        assertEquals(70.0, ticks.get("msptAvg").getAsDouble());
        assertEquals(100.0, ticks.get("max").getAsDouble());
        assertEquals(1, ticks.get("over50ms").getAsInt());
        JsonObject slowest = ticks.getAsJsonArray("slowest").get(0).getAsJsonObject();
        assertEquals(2, slowest.get("tick").getAsLong());
        assertEquals(100.0, slowest.get("ms").getAsDouble());
        assertEquals("Other", slowest.getAsJsonArray("owners").get(0).getAsJsonObject().get("owner").getAsString());
    }

    @Test
    void noTicksNoTicksField() {
        ProfileReport r = new ProfileReport(owners);
        r.add(SERVER_LOOP, 0);
        assertFalse(r.json(5).has("ticks"));
    }

    @Test
    void countsTruncatedStacks() {
        ProfileReport r = new ProfileReport(owners);
        r.add(SERVER_LOOP, 0, true);
        r.add(SERVER_LOOP, 0, false);
        assertEquals(1, r.json(5).get("truncatedStacks").getAsInt());
    }

    @Test
    void limitsEachListToTop() {
        ProfileReport r = new ProfileReport(owners);
        for (String m : Arrays.asList("a", "b", "c", "d")) r.add(stack("net.minecraft.X." + m), 0);
        assertEquals(2, r.json(2).getAsJsonArray("hotMethods").size());
    }
}
