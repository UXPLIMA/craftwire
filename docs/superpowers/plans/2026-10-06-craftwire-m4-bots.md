# Craftwire M4 — Bots Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This project runs it inline (superpowers:executing-plans): the user does not want subagents.**

**Goal:** Claude can put server-side fake players ("bots") on a Paper server and drive them like real players: chat, commands (with the replies the bot receives), walking to a point, looking, using items and blocks, attacking, reading and clicking plugin GUIs, giving items and switching the hotbar slot.

**Architecture:** Bots live in one NMS package, `agent-paper/.../paper/bot/`. That package is compiled against Paper's Mojang-mapped internals through paperweight-userdev; the rest of the plugin keeps using only the Bukkit/Paper API.

A bot is a real `ServerPlayer`:
- It joins through `PlayerList.placeNewPlayer`, using a `Connection` whose channel is a Netty `EmbeddedChannel`.
- The channel's outbound handler swallows packets but records the chat and action-bar messages the bot receives (its "inbox").
- A single repeating task ticks every bot. Each tick it steers walking (yaw + forward input + auto-jump), calls `doTick()` (the server never does this for a player without a client), respawns dead bots, and drops bots that were kicked.

Bots act through the server's own packet handlers (`handleContainerClick`, `handleUseItemOn`, `handleAttack`, …). Plugins therefore see the same events a real client would cause: `InventoryClickEvent`, `PlayerInteractEvent`, `EntityDamageByEntityEvent`, and so on. The hub adds three MCP tools that forward to the agent.

**Tech Stack:** Java 25, Paper 26.2 (build 130) with paperweight-userdev 2.0.0-beta.24 and dev bundle `26.2.build.130-stable`, JUnit 5, Netty `EmbeddedChannel`, TypeScript hub (zod, Vitest).

**Spec:** `docs/superpowers/specs/2026-10-05-craftwire-design.md`. Relevant parts: §2 Bots, §4 the `bot_spawn` / `bot_remove` / `bot_action` rows, §5 `allow-bots`, §7 "Paper agent … bots", §9 M4, and the §10 risk "Fake players on Paper 26.x … isolated in one package".

**Feasibility evidence (spike, 2026-10-06, discarded):** a throwaway `BotSpike` handler and IT ran on real Paper 26.2 build 130. Findings:
- The fake player joined and the join event fired.
- With `doTick()` every tick it fell and landed; without it, it floated.
- `chat` and `performCommand` worked.
- It was not kicked after 23 s.
- `handleContainerClick` with `ServerboundContainerClickPacket(containerId, stateId, slot, button, ContainerInput.PICKUP, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY)` fired `InventoryClickEvent` with the right slot, click type and title.
- Walking with yaw + `zza = 1` before `doTick()` covered about 4.3 blocks/s.
- `connection.disconnect(...)` did **not** remove the bot; `PlayerList.remove(player)` did, and the quit event fired.

NMS signatures used below were all checked with `javap` against the 26.2 server jar.

## Global Constraints

- Every NMS import (`net.minecraft.*`, `org.bukkit.craftbukkit.*`, `io.netty.*`) stays inside `com.uxplima.craftwire.paper.bot`.
- The plugin jar stays Mojang-mapped: no reobf. Its manifest carries `paperweight-mappings-namespace: mojang`.
- `allow-bots: false` in `plugins/Craftwire/config.yml` makes every bot method answer `PERMISSION_DISABLED`.
- All bot state is touched on the server main thread (`Sync.global`, scheduler task). The plugin stays `folia-supported: false`.
- Bots are removed when the plugin disables. They survive hub reconnects.
- Errors keep `{code, message, hint}`. New codes:
  - `BOT_NOT_FOUND`, `NAME_TAKEN`, `OUT_OF_REACH`, `ENTITY_NOT_FOUND`;
  - `NO_SCREEN_OPEN` and `SLOT_OUT_OF_RANGE`, reused from the client tools.
- Lockstep version `0.4.0`. Hub dependencies are unchanged.
- Integration tests share one Paper server. Every bot test removes its bots afterwards (`WorldQueryIT.playersIsEmptyWithNobodyOnline` depends on that).
- Local Gradle needs `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1"`.

## Review Focus

1. **A plugin kicks or bans a bot** (`/kick Bot1`). The bot must leave: quit event, gone from the player list and from the manager. It must not stay as an "online" ghost, because `connection.disconnect` alone does not remove it (test: Task 3 `kickedBotLeaves`).
2. **A bot dies** (mob, `/kill`). It must respawn within about 1 s instead of lying dead forever, and a running `move_to` must end with `reason: "died"` (test: Task 4 `deadBotRespawns`).
3. **move_to toward an unreachable point.** It must end (`stuck` or `timeout`) and never hang the tool call. It must also hop a one-block step (tests: Task 4 `moveToJumpsOneBlockSteps`, `moveToStopsWhenStuck`, `moveToTimesOut`).
4. **Plugin GUIs that react a tick later** (reopen or close the menu, send a message). `gui_click` must return the state after the plugin reacted (test: Task 5 `guiClickSeesThePluginsReaction`).
5. **A bot name equal to an online real player.** It must be refused (`NAME_TAKEN`), never collide with or replace the player (test: Task 3 `nameTakenAndBadNames`).

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle` | PaperMC repo in `pluginManagement` (paperweight plugin) |
| `agent-paper/build.gradle` | paperweight-userdev + dev bundle instead of `paper-api` |
| `agent-paper/.../paper/bot/BotNames.java` | Name validation and allocation (pure) |
| `agent-paper/.../paper/bot/Steering.java` | Yaw/pitch maths, stuck detection (pure) |
| `agent-paper/.../paper/bot/BotInbox.java` | Ring buffer of messages a bot received (pure) |
| `agent-paper/.../paper/bot/FakeConnection.java` | `Connection` over an `EmbeddedChannel` that records chat packets |
| `agent-paper/.../paper/bot/Bot.java` | One bot: join, tick (steer, doTick, respawn, leave), remove, move |
| `agent-paper/.../paper/bot/BotActions.java` | The `bot.action` verbs |
| `agent-paper/.../paper/bot/BotJson.java` | Item / GUI / state JSON |
| `agent-paper/.../paper/bot/BotManager.java` | Name → bot, tick task, spawn/remove, shutdown |
| `agent-paper/.../paper/handlers/BotHandler.java` | `bot.spawn`, `bot.remove`, `bot.action` RPC entry points |
| `test-fixtures/.../FixturePlugin.java` | `/cwfixture menu`: a chest GUI that reacts to clicks |
| `hub/src/tools/bot-tools.ts` | `bot_spawn`, `bot_remove`, `bot_action` MCP tools |

---

### Task 1: Compile agent-paper against Paper internals (paperweight-userdev)

**Files:**
- Modify: `settings.gradle`, `agent-paper/build.gradle`
- Test: `agent-paper/src/test/java/com/uxplima/craftwire/paper/bot/NmsClasspathTest.java`

**Interfaces:**
- Produces: `agent-paper` main and test code can import `net.minecraft.*` and `org.bukkit.craftbukkit.*`; the plugin jar is unchanged otherwise.

- [ ] **Step 1: Write the failing test**

```java
package com.uxplima.craftwire.paper.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NmsClasspathTest {
    @Test
    void serverInternalsAreOnTheCompileClasspath() throws Exception {
        // Loaded without initialising: no Minecraft bootstrap in unit tests.
        Class<?> c = Class.forName("net.minecraft.server.level.ServerPlayer", false, getClass().getClassLoader());
        assertEquals("ServerPlayer", c.getSimpleName());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test --tests '*NmsClasspathTest*'`
Expected: FAIL with `ClassNotFoundException: net.minecraft.server.level.ServerPlayer`.

- [ ] **Step 3: Add paperweight-userdev**

- `settings.gradle`, in `pluginManagement.repositories` after the Fabric line, add:

```groovy
        maven { name = 'PaperMC'; url = 'https://repo.papermc.io/repository/maven-public/' }
```

- `agent-paper/build.gradle`, plugins block:

```groovy
plugins {
    id 'java'
    id 'io.papermc.paperweight.userdev' version '2.0.0-beta.24'
}
```

- In `dependencies`, replace `compileOnly "io.papermc.paper:paper-api:${paper_api_version}"` with:

```groovy
    // Paper API plus Mojang-mapped server internals; only the bot package touches the internals.
    paperweight.paperDevBundle("${paper_api_version}")
```

- Remove `testImplementation "io.papermc.paper:paper-api:${paper_api_version}"`; the dev bundle already puts the API on the test classpath.

- [ ] **Step 4: Run the tests and check the jar**

Run:
```bash
JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test :agent-paper:build
unzip -p agent-paper/build/libs/craftwire-paper-0.3.0.jar META-INF/MANIFEST.MF
```
Expected:
- `NmsClasspathTest` passes, as do all existing unit tests.
- The manifest contains `paperweight-mappings-namespace: mojang`.

If the attribute is missing, add `jar { manifest { attributes('paperweight-mappings-namespace': 'mojang') } }` and ledger a ruling: without it, Paper would treat the jar as Spigot-mapped and remap it at startup.

- [ ] **Step 5: Run the integration suite (nothing else changed)**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:integrationTest :agent-fabric:build :test-fixtures:build`
Expected: BUILD SUCCESSFUL, 44 ITs pass. Loom and paperweight coexist; the spike showed this.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle agent-paper/build.gradle agent-paper/src/test/java/com/uxplima/craftwire/paper/bot/NmsClasspathTest.java
git commit -m "build(paper): compile against Paper internals with paperweight-userdev"
```

---

### Task 2: Pure bot logic — names, steering, inbox

**Files:**
- Create:
  - `agent-paper/src/main/java/com/uxplima/craftwire/paper/bot/BotNames.java`
  - `agent-paper/src/main/java/com/uxplima/craftwire/paper/bot/Steering.java`
  - `agent-paper/src/main/java/com/uxplima/craftwire/paper/bot/BotInbox.java`
- Test:
  - `agent-paper/src/test/java/com/uxplima/craftwire/paper/bot/BotNamesTest.java`
  - `SteeringTest.java`, `BotInboxTest.java` (same folder)

**Interfaces:**
- Produces:
  - `BotNames.validate(String) → String` (`INVALID_PARAMS` unless `[A-Za-z0-9_]{3,16}`).
  - `BotNames.allocate(String prefix, int count, Predicate<String> taken) → List<String>`.
  - `Steering.yaw(dx, dz) → float`, `Steering.pitch(dx, dy, dz) → float`, `Steering.horizontal(dx, dz) → double`.
  - `new Steering.Progress(double minGain, long windowMs)` with `boolean stuck(double distance, long now)`.
  - `new BotInbox(int capacity)` with `add(String kind, String text, String sender, long time)`, `List<BotInbox.Message> since(long time, int limit)`, and `record Message(long time, String kind, String text, String sender)`.

- [ ] **Step 1: Write the failing tests**

`BotNamesTest.java`:

```java
package com.uxplima.craftwire.paper.bot;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.core.AgentError;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BotNamesTest {
    @Test
    void allocatesNumberedNamesSkippingTakenOnes() {
        assertEquals(List.of("Bot1", "Bot3", "Bot4"), BotNames.allocate("Bot", 3, Set.of("Bot2")::contains));
    }

    @Test
    void skipsNamesShorterThanThree() {
        assertEquals(List.of("B10"), BotNames.allocate("B", 1, n -> false));
    }

    @Test
    void rejectsBadNamesAndPrefixes() {
        assertEquals("Tester_2", BotNames.validate("Tester_2"));
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> BotNames.validate("ab")).code());
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> BotNames.validate("no spaces")).code());
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> BotNames.allocate("ThisPrefixIsTooLong", 1, n -> false)).code());
    }

    @Test
    void failsWhenNumbersNoLongerFit() {
        // 13-char prefix leaves room for numbers up to 999.
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> BotNames.allocate("Abcdefghijklm", 1, n -> true)).code());
    }
}
```

`SteeringTest.java`:

```java
package com.uxplima.craftwire.paper.bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SteeringTest {
    @Test
    void yawFollowsMinecraftConventions() {
        assertEquals(0f, Steering.yaw(0, 1), 1e-4);      // south (+Z)
        assertEquals(-90f, Steering.yaw(1, 0), 1e-4);    // east (+X)
        assertEquals(90f, Steering.yaw(-1, 0), 1e-4);    // west
        assertEquals(180f, Math.abs(Steering.yaw(0, -1)), 1e-4); // north
    }

    @Test
    void pitchIsPositiveLookingDown() {
        assertEquals(45f, Steering.pitch(1, -1, 0), 1e-4);
        assertEquals(-45f, Steering.pitch(0, 1, 1), 1e-4);
        assertEquals(5.0, Steering.horizontal(3, 4), 1e-9);
    }

    @Test
    void progressReportsStuckOnlyAfterTheWindowWithoutGain() {
        Steering.Progress p = new Steering.Progress(0.3, 2000);
        assertFalse(p.stuck(10, 0));
        assertFalse(p.stuck(9, 1000));      // gained 1 block
        assertFalse(p.stuck(8.9, 2900));    // < 0.3 gain, but only 1.9 s since the last gain
        assertTrue(p.stuck(8.9, 3001));
    }
}
```

`BotInboxTest.java`:

```java
package com.uxplima.craftwire.paper.bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BotInboxTest {
    @Test
    void keepsTheNewestMessagesAndFiltersByTime() {
        BotInbox inbox = new BotInbox(3);
        inbox.add("system", "a", null, 1);
        inbox.add("system", "b", null, 2);
        inbox.add("chat", "c", "Steve", 3);
        inbox.add("actionbar", "d", null, 4);
        assertEquals(java.util.List.of("b", "c", "d"), inbox.since(0, 50).stream().map(BotInbox.Message::text).toList());
        assertEquals(java.util.List.of("c", "d"), inbox.since(3, 50).stream().map(BotInbox.Message::text).toList());
        assertEquals(java.util.List.of("d"), inbox.since(0, 1).stream().map(BotInbox.Message::text).toList());
        assertEquals("Steve", inbox.since(3, 50).getFirst().sender());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test --tests '*bot*'`
Expected: compilation FAIL, "cannot find symbol: class BotNames" (and Steering, BotInbox).

- [ ] **Step 3: Implement**

`BotNames.java`:

```java
package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Minecraft player names: 3-16 letters, digits or underscores. */
public final class BotNames {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]{1,13}");

    private BotNames() {}

    public static String validate(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new AgentError("INVALID_PARAMS", "Bot names are 3-16 letters, digits or _: " + name, "Use names like Bot1 or Tester_2.");
        }
        return name;
    }

    /** `count` names prefix1, prefix2, … that `taken` does not report as in use. */
    public static List<String> allocate(String prefix, int count, Predicate<String> taken) {
        if (!PREFIX.matcher(prefix).matches()) {
            throw new AgentError("INVALID_PARAMS", "namePrefix must be 1-13 letters, digits or _", "Use a short prefix like Bot.");
        }
        List<String> out = new ArrayList<>();
        for (int i = 1; out.size() < count; i++) {
            String name = prefix + i;
            if (name.length() > 16) {
                throw new AgentError("INVALID_PARAMS", "No free names left for prefix " + prefix, "Use a shorter namePrefix, or remove some bots.");
            }
            if (name.length() >= 3 && !taken.test(name)) out.add(name);
        }
        return out;
    }
}
```

`Steering.java`:

```java
package com.uxplima.craftwire.paper.bot;

/** Rotation maths in Minecraft's conventions (yaw 0 = south/+Z, -90 = east/+X; pitch > 0 looks down). */
public final class Steering {
    private Steering() {}

    public static float yaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    public static float pitch(double dx, double dy, double dz) {
        return (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
    }

    public static double horizontal(double dx, double dz) {
        return Math.hypot(dx, dz);
    }

    /** Detects a walker that stopped getting closer: no gain of `minGain` blocks for `windowMs`. */
    public static final class Progress {
        private final double minGain;
        private final long windowMs;
        private double best = Double.POSITIVE_INFINITY;
        private long bestAt;

        public Progress(double minGain, long windowMs) {
            this.minGain = minGain;
            this.windowMs = windowMs;
        }

        public boolean stuck(double distance, long now) {
            if (distance < best - minGain) {
                best = distance;
                bestAt = now;
                return false;
            }
            return now - bestAt > windowMs;
        }
    }
}
```

`BotInbox.java`:

```java
package com.uxplima.craftwire.paper.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Messages a bot received (chat, system lines, action bar), newest last. Thread-safe: Netty may write off-thread. */
public final class BotInbox {
    public record Message(long time, String kind, String text, String sender) {}

    private final Deque<Message> items = new ArrayDeque<>();
    private final int capacity;

    public BotInbox(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(String kind, String text, String sender, long time) {
        items.addLast(new Message(time, kind, text, sender));
        while (items.size() > capacity) items.removeFirst();
    }

    /** Messages at or after `time`, at most the newest `limit`. */
    public synchronized List<Message> since(long time, int limit) {
        List<Message> out = new ArrayList<>();
        for (Message m : items) if (m.time() >= time) out.add(m);
        return out.subList(Math.max(0, out.size() - limit), out.size());
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-paper/src/main/java/com/uxplima/craftwire/paper/bot agent-paper/src/test/java/com/uxplima/craftwire/paper/bot
git commit -m "feat(paper): bot names, steering maths and message inbox"
```

---

### Task 3: Bots join, tick, leave — spawn and remove

**Files:**
- Create:
  - `agent-paper/src/main/java/com/uxplima/craftwire/paper/bot/FakeConnection.java`
  - `agent-paper/src/main/java/com/uxplima/craftwire/paper/bot/Bot.java`
  - `agent-paper/src/main/java/com/uxplima/craftwire/paper/bot/BotManager.java`
  - `agent-paper/src/main/java/com/uxplima/craftwire/paper/bot/BotJson.java`
  - `agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/BotHandler.java`
  - `agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/BotLifecycleIT.java`
- Modify:
  - `CraftwirePlugin.java` (field `bots`, start in onEnable, shutdown in onDisable)
  - `handlers/Handlers.java`
  - `handlers/WorldQueryHandler.java` (`bot` flag in players)

**Interfaces:**
- Consumes (Task 2): `BotNames`, `BotInbox`, `Steering`.
- Produces:
  - `Bot`: `static Bot join(String name, Location at)`, `String name()`, `UUID uuid()`, `ServerPlayer player()`, `Player bukkit()`, `ServerGamePacketListenerImpl listener()`, `BotInbox inbox()`, `int nextSequence()`, `boolean tick(long now)`, `void remove()`, `CompletableFuture<JsonObject> moveTo(double x, double y, double z, double tolerance, boolean sprint, long timeoutMs)`, `boolean moving()`.
  - `BotManager(Plugin)`: `start()`, `shutdown()`, `isBot(String)`, `get(String)` (`BOT_NOT_FOUND`), `List<Bot> spawn(List<String>, Location)` (`NAME_TAKEN`), `Bot remove(String)`, `List<String> removeAll()`.
  - `CraftwirePlugin.bots()`.
  - RPC:
    - `bot.spawn {count?, namePrefix?, names?, location?{world?,x,y,z,yaw?,pitch?}}` → `{bots:[{name, uuid, world, x, y, z}]}`;
    - `bot.remove {name? | all:true}` → `{removed:[names]}`;
    - `world.query players` rows gain `bot: boolean`.

- [ ] **Step 1: Write the failing integration test**

`BotLifecycleIT.java`:

```java
package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BotLifecycleIT {
    final ItHub hub = ItEnv.get().hub;

    @AfterEach
    void removeBots() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
    }

    JsonArray players() throws Exception {
        return hub.result("world.query", "{\"action\":\"players\"}").getAsJsonObject().getAsJsonArray("players");
    }

    JsonObject player(String name) throws Exception {
        for (JsonElement e : players()) if (e.getAsJsonObject().get("name").getAsString().equals(name)) return e.getAsJsonObject();
        return null;
    }

    @Test
    void spawnJoinsAsRealPlayersAndRemoveQuits() throws Exception {
        JsonArray bots = hub.result("bot.spawn", "{\"count\":2,\"namePrefix\":\"It\"}").getAsJsonObject().getAsJsonArray("bots");
        assertEquals("It1", bots.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("It2", bots.get(1).getAsJsonObject().get("name").getAsString());
        hub.awaitEvent(e -> e.toString().contains("\"action\":\"join\"") && e.toString().contains("It2"), 5000);
        assertTrue(player("It1").get("bot").getAsBoolean());
        JsonArray removed = hub.result("bot.remove", "{\"all\":true}").getAsJsonObject().getAsJsonArray("removed");
        assertEquals(2, removed.size());
        hub.awaitEvent(e -> e.toString().contains("\"action\":\"quit\"") && e.toString().contains("It1"), 5000);
        assertEquals(0, players().size());
    }

    @Test
    void botsFallAndStandOnTheGround() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"ItFall\"],\"location\":{\"x\":3.5,\"y\":-54,\"z\":3.5}}");
        Thread.sleep(2000);
        JsonObject p = player("ItFall");
        assertEquals(-60.0, p.get("y").getAsDouble(), 0.01, p.toString()); // flat world surface
    }

    @Test
    void nameTakenAndBadNames() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"ItTwin\"]}");
        assertEquals("NAME_TAKEN", hub.error("bot.spawn", "{\"names\":[\"ittwin\"]}").get("code").getAsString());
        assertEquals("INVALID_PARAMS", hub.error("bot.spawn", "{\"names\":[\"x\"]}").get("code").getAsString());
        assertEquals("BOT_NOT_FOUND", hub.error("bot.remove", "{\"name\":\"Nobody\"}").get("code").getAsString());
    }

    @Test
    void kickedBotLeaves() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"ItKick\"]}");
        hub.result("server.command", "{\"command\":\"minecraft:kick ItKick bye\"}");
        Thread.sleep(1000);
        assertNull(player("ItKick"));
        assertEquals("BOT_NOT_FOUND", hub.error("bot.remove", "{\"name\":\"ItKick\"}").get("code").getAsString());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:integrationTest --tests '*BotLifecycleIT*'`
Expected: FAIL. The `@AfterEach` call gets `UNKNOWN_METHOD` for `bot.remove`, so `AssertionError: bot.remove failed`.

- [ ] **Step 3: Implement FakeConnection, Bot, BotManager, BotJson, BotHandler**

`FakeConnection.java`:

```java
package com.uxplima.craftwire.paper.bot;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * A server-side connection with no client: outbound packets are dropped, except that chat and action-bar
 * messages are copied into the bot's inbox. EmbeddedChannel runs writes inline, so nothing queues up.
 */
final class FakeConnection {
    private FakeConnection() {}

    static Connection create(BotInbox inbox) {
        Connection c = new Connection(PacketFlow.SERVERBOUND);
        c.channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                try {
                    record(inbox, msg, System.currentTimeMillis());
                } finally {
                    ReferenceCountUtil.release(msg);
                    promise.setSuccess();
                }
            }
        });
        c.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        return c;
    }

    static void record(BotInbox inbox, Object msg, long now) {
        if (msg instanceof ClientboundSystemChatPacket p) {
            inbox.add(p.overlay() ? "actionbar" : "system", p.content().getString(), null, now);
        } else if (msg instanceof ClientboundPlayerChatPacket p) {
            String text = p.unsignedContent() != null ? p.unsignedContent().getString() : p.body().content();
            inbox.add("chat", text, p.chatType().name().getString(), now);
        } else if (msg instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) record(inbox, sub, now);
        }
    }
}
```

`Bot.java`:

```java
package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

/** One fake player. Every method runs on the server thread. */
public final class Bot {
    private static final int RESPAWN_TICKS = 20;

    private final String name;
    private final UUID uuid;
    private final Connection connection;
    private final ServerGamePacketListenerImpl listener;
    private final BotInbox inbox;
    private int sequence;
    private int deadTicks;
    private Move move;

    private Bot(String name, UUID uuid, Connection connection, ServerGamePacketListenerImpl listener, BotInbox inbox) {
        this.name = name;
        this.uuid = uuid;
        this.connection = connection;
        this.listener = listener;
        this.inbox = inbox;
    }

    /** Joins the server like a client would (join event, tab list, player data), then moves to `at`. */
    static Bot join(String name, Location at) {
        MinecraftServer server = MinecraftServer.getServer();
        ServerLevel level = ((CraftWorld) at.getWorld()).getHandle();
        // Offline-mode UUID: never the same as a real Mojang account's UUID.
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, name);
        BotInbox inbox = new BotInbox(200);
        Connection connection = FakeConnection.create(inbox);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        Bot bot = new Bot(name, uuid, connection, player.connection, inbox);
        bot.bukkit().teleport(at);
        return bot;
    }

    public String name() { return name; }
    public UUID uuid() { return uuid; }
    public BotInbox inbox() { return inbox; }
    public ServerGamePacketListenerImpl listener() { return listener; }
    /** The current player entity; a respawn replaces it, the listener follows. */
    public ServerPlayer player() { return listener.player; }
    public Player bukkit() { return player().getBukkitEntity(); }
    public int nextSequence() { return ++sequence; }
    public boolean moving() { return move != null; }

    /** One server tick. Returns false once the bot is gone (kicked, banned, disconnected). */
    boolean tick(long now) {
        if (!connection.isConnected()) {
            leave();
            return false;
        }
        ServerPlayer p = player();
        if (p.isDeadOrDying()) {
            if (move != null) finish("died");
            if (++deadTicks >= RESPAWN_TICKS) {
                deadTicks = 0;
                listener.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            }
            return true;
        }
        deadTicks = 0;
        if (move != null) steer(p, now);
        // A real client's movement packets make the server tick the player; a bot has none, so tick it here.
        p.doTick();
        return true;
    }

    /** Leaves the server now (quit event, player data saved). */
    void remove() {
        if (move != null) finish("removed");
        MinecraftServer.getServer().getPlayerList().remove(player());
        connection.channel.close();
    }

    /** Something else closed the connection (a kick): run the normal disconnect path once. */
    private void leave() {
        if (move != null) finish("removed");
        connection.handleDisconnection();
        var list = MinecraftServer.getServer().getPlayerList();
        if (list.getPlayer(uuid) != null) list.remove(player());
    }

    CompletableFuture<JsonObject> moveTo(double x, double y, double z, double tolerance, boolean sprint, long timeoutMs) {
        if (move != null) finish("replaced");
        move = new Move(x, y, z, tolerance, sprint, System.currentTimeMillis() + timeoutMs);
        return move.done;
    }

    private void steer(ServerPlayer p, long now) {
        Vec3 pos = p.position();
        double dx = move.x - pos.x;
        double dz = move.z - pos.z;
        double distance = Steering.horizontal(dx, dz);
        if (distance <= move.tolerance && Math.abs(move.y - pos.y) <= 1.5) {
            finish("arrived");
        } else if (now >= move.deadline) {
            finish("timeout");
        } else if (move.progress.stuck(distance, now)) {
            finish("stuck");
        } else {
            float yaw = Steering.yaw(dx, dz);
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.zza = 1.0f;
            p.setSprinting(move.sprint);
            p.setJumping(p.horizontalCollision && p.onGround());
        }
    }

    private void finish(String reason) {
        ServerPlayer p = player();
        p.zza = 0f;
        p.setJumping(false);
        p.setSprinting(false);
        Move m = move;
        move = null;
        Vec3 pos = p.position();
        JsonObject r = new JsonObject();
        r.addProperty("reached", reason.equals("arrived"));
        r.addProperty("reason", reason);
        r.addProperty("x", round(pos.x));
        r.addProperty("y", round(pos.y));
        r.addProperty("z", round(pos.z));
        r.addProperty("distance", round(Steering.horizontal(m.x - pos.x, m.z - pos.z)));
        m.done.complete(r);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static final class Move {
        final double x, y, z, tolerance;
        final boolean sprint;
        final long deadline;
        final Steering.Progress progress = new Steering.Progress(0.3, 2000);
        final CompletableFuture<JsonObject> done = new CompletableFuture<>();

        Move(double x, double y, double z, double tolerance, boolean sprint, long deadline) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.tolerance = tolerance;
            this.sprint = sprint;
            this.deadline = deadline;
        }
    }
}
```

`BotManager.java`:

```java
package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** All bots of this server, ticked by one repeating task on the main thread. */
public final class BotManager {
    private final Plugin plugin;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private BukkitTask task;

    public BotManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        removeAll();
    }

    public boolean isBot(String name) {
        return bots.containsKey(key(name));
    }

    public Bot get(String name) {
        Bot b = bots.get(key(name));
        if (b == null) {
            throw new AgentError("BOT_NOT_FOUND", "No bot named " + name,
                    "bot_spawn creates bots; world_query {action:'players'} lists them (bot:true).");
        }
        return b;
    }

    public List<Bot> spawn(List<String> names, Location at) {
        for (String n : names) {
            BotNames.validate(n);
            if (isBot(n) || Bukkit.getPlayerExact(n) != null) {
                throw new AgentError("NAME_TAKEN", n + " is already online", "Pick other names, or a different namePrefix.");
            }
        }
        List<Bot> out = new ArrayList<>();
        for (String n : names) {
            Bot b = Bot.join(n, at);
            bots.put(key(n), b);
            out.add(b);
        }
        return out;
    }

    public Bot remove(String name) {
        Bot b = get(name);
        bots.remove(key(name));
        b.remove();
        return b;
    }

    public List<String> removeAll() {
        List<String> names = new ArrayList<>();
        for (Bot b : List.copyOf(bots.values())) {
            names.add(b.name());
            try {
                b.remove();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not remove bot " + b.name(), e);
            }
        }
        bots.clear();
        return names;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Bot> it = bots.values().iterator(); it.hasNext(); ) {
            Bot b = it.next();
            try {
                if (!b.tick(now)) it.remove();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Bot " + b.name() + " failed and was removed", e);
                it.remove();
                try { b.remove(); } catch (RuntimeException ignored) { /* already broken */ }
            }
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
```

`BotJson.java` (this task needs only `summary`; Tasks 4–5 extend it):

```java
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
```

`handlers/BotHandler.java`:

```java
package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.bot.Bot;
import com.uxplima.craftwire.paper.bot.BotJson;
import com.uxplima.craftwire.paper.bot.BotNames;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

final class BotHandler {
    private BotHandler() {}

    static CompletableFuture<JsonElement> spawn(JsonObject p, CraftwirePlugin self) {
        self.agentConfig().require(self.agentConfig().allowBots(), "allow-bots");
        return self.sync().global(() -> {
            List<String> names = new ArrayList<>();
            if (p.has("names")) {
                for (JsonElement e : Args.array(p, "names")) names.add(BotNames.validate(e.getAsString()));
            } else {
                int count = Math.clamp(Args.optInt(p, "count").orElse(1), 1, 20);
                String prefix = Args.optString(p, "namePrefix").orElse("Bot");
                names = BotNames.allocate(prefix, count, n -> self.bots().isBot(n) || Bukkit.getPlayerExact(n) != null);
            }
            JsonArray out = new JsonArray();
            for (Bot b : self.bots().spawn(names, location(p))) out.add(BotJson.summary(b));
            JsonObject r = new JsonObject();
            r.add("bots", out);
            return r;
        });
    }

    static CompletableFuture<JsonElement> remove(JsonObject p, CraftwirePlugin self) {
        self.agentConfig().require(self.agentConfig().allowBots(), "allow-bots");
        return self.sync().global(() -> {
            JsonArray removed = new JsonArray();
            if (Args.bool(p, "all", false)) {
                self.bots().removeAll().forEach(removed::add);
            } else {
                removed.add(self.bots().remove(Args.string(p, "name")).name());
            }
            JsonObject r = new JsonObject();
            r.add("removed", removed);
            return r;
        });
    }

    /** `location` {world?, x, y, z, yaw?, pitch?}, else the main world's spawn (block centre). */
    private static Location location(JsonObject p) {
        if (!p.has("location")) {
            World w = Bukkit.getWorlds().getFirst();
            return w.getSpawnLocation().toCenterLocation().subtract(0, 0.5, 0);
        }
        JsonObject l = Args.object(p, "location");
        World w = Args.world(l);
        float yaw = (float) (l.has("yaw") ? l.get("yaw").getAsDouble() : 0);
        float pitch = (float) (l.has("pitch") ? l.get("pitch").getAsDouble() : 0);
        return new Location(w, Args.number(l, "x"), Args.number(l, "y"), Args.number(l, "z"), yaw, pitch);
    }
}
```

`Handlers.registerAll`, add:

```java
        d.register("bot.spawn", p -> BotHandler.spawn(p, plugin));
        d.register("bot.remove", p -> BotHandler.remove(p, plugin));
```

Change the world query registration to pass the bot check:

```java
        d.register("world.query", p -> WorldQueryHandler.handle(p, s, plugin.bots()::isBot));
```

`WorldQueryHandler`:
- `handle(JsonObject p, Sync sync)` becomes `handle(JsonObject p, Sync sync, java.util.function.Predicate<String> isBot)`.
- `case "players" -> sync.global(() -> players(isBot));`
- `players(Predicate<String> isBot)` adds `o.addProperty("bot", isBot.test(pl.getName()));`.

`CraftwirePlugin`:
- field `private BotManager bots;`
- in onEnable, before `Handlers.registerAll(this)`: `bots = new BotManager(this); bots.start();`
- in onDisable, first line: `if (bots != null) bots.shutdown();`
- accessor `public BotManager bots() { return bots; }`
- import `com.uxplima.craftwire.paper.bot.BotManager`.

- [ ] **Step 4: Run the bot IT and the full IT suite**

Run:
```bash
JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test :agent-paper:integrationTest > .superpowers/sdd/2026-10-06-craftwire-m4-bots/t3.log 2>&1; tail -20 .superpowers/sdd/2026-10-06-craftwire-m4-bots/t3.log
```
Expected:
- `BotLifecycleIT` 4/4 pass.
- The other 44 ITs still pass, including `WorldQueryIT.playersIsEmptyWithNobodyOnline` (bots were removed).

- [ ] **Step 5: Commit**

```bash
git add agent-paper/src
git commit -m "feat(paper): bots join as real players, tick, respawn and leave"
```

---

### Task 4: Bot actions — chat, command, messages, look, move_to, state, give, select_hotbar

**Files:**
- Create:
  - `agent-paper/src/main/java/com/uxplima/craftwire/paper/bot/BotActions.java`
  - `agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/BotActionsIT.java`
- Modify:
  - `bot/BotJson.java` (item, state, messages)
  - `handlers/BotHandler.java` (`action`)
  - `handlers/Handlers.java` (`bot.action`)

**Interfaces:**
- Consumes (Task 3): `Bot`, `BotManager.get`, `BotJson.round`.
- Produces: RPC `bot.action {bot, action, …}` with these verbs:
  - `chat {text}` → `{sent:true}`
  - `command {command, collectMs=300}` → `{command, success, messages:[{time,kind,text,sender?}]}`
  - `messages {since?, limit=50}` → `{messages:[…]}`
  - `look {yaw, pitch}` or `{x, y, z}` → `{yaw, pitch}`
  - `move_to {x, y, z, tolerance=0.5, sprint=false, timeoutMs=10000}` → `{reached, reason: arrived|timeout|stuck|died|removed|replaced, x, y, z, distance}`
  - `state` → `{name, uuid, world, x, y, z, yaw, pitch, health, food, gameMode, onGround, dead, moving, heldSlot, held, inventory:[{slot,id,count,name,lore?}], openMenu}`
  - `give {item, count=1}` → `{given, leftover}`
  - `select_hotbar {slot 0-8}` → `{slot, held}`
- Also produces `BotJson.item(ItemStack)` (`{id, count, name, lore?}`) and `BotJson.messages(List<BotInbox.Message>)`.

- [ ] **Step 1: Write the failing integration test**

`BotActionsIT.java`:

```java
package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BotActionsIT {
    final ItHub hub = ItEnv.get().hub;

    @BeforeEach
    void spawn() throws Exception {
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":-2,\"y\":-60,\"z\":-2},\"max\":{\"x\":30,\"y\":-56,\"z\":6},\"block\":\"air\"}");
        hub.result("bot.spawn", "{\"names\":[\"Act\"],\"location\":{\"x\":0.5,\"y\":-60,\"z\":0.5}}");
        Thread.sleep(300);
    }

    @AfterEach
    void cleanUp() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":-2,\"y\":-60,\"z\":-2},\"max\":{\"x\":30,\"y\":-56,\"z\":6},\"block\":\"air\"}");
    }

    JsonObject act(String json) throws Exception {
        return hub.result("bot.action", json).getAsJsonObject();
    }

    @Test
    void chatReachesPluginsAsAPlayerMessage() throws Exception {
        act("{\"bot\":\"Act\",\"action\":\"chat\",\"text\":\"hello from a bot\"}");
        hub.awaitEvent(e -> e.get("type").getAsString().equals("chat") && e.toString().contains("hello from a bot"), 5000);
    }

    @Test
    void commandReturnsTheRepliesTheBotReceived() throws Exception {
        JsonObject r = act("{\"bot\":\"Act\",\"action\":\"command\",\"command\":\"/cwfixture\",\"collectMs\":400}");
        assertTrue(r.get("success").getAsBoolean());
        String texts = r.getAsJsonArray("messages").toString();
        assertTrue(texts.contains("fixture: now") && texts.contains("fixture: later"), texts);
        assertTrue(act("{\"bot\":\"Act\",\"action\":\"messages\"}").getAsJsonArray("messages").toString().contains("fixture: later"));
    }

    @Test
    void moveToWalksToTheTarget() throws Exception {
        JsonObject r = act("{\"bot\":\"Act\",\"action\":\"move_to\",\"x\":10.5,\"y\":-60,\"z\":0.5}");
        assertTrue(r.get("reached").getAsBoolean(), r.toString());
        assertEquals(10.5, r.get("x").getAsDouble(), 0.6);
    }

    @Test
    void moveToJumpsOneBlockSteps() throws Exception {
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":5,\"y\":-60,\"z\":-2},\"max\":{\"x\":5,\"y\":-60,\"z\":2},\"block\":\"stone\"}");
        JsonObject r = act("{\"bot\":\"Act\",\"action\":\"move_to\",\"x\":10.5,\"y\":-60,\"z\":0.5}");
        assertTrue(r.get("reached").getAsBoolean(), r.toString());
    }

    @Test
    void moveToStopsWhenStuck() throws Exception {
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":5,\"y\":-60,\"z\":-2},\"max\":{\"x\":5,\"y\":-57,\"z\":3},\"block\":\"stone\"}");
        JsonObject r = act("{\"bot\":\"Act\",\"action\":\"move_to\",\"x\":10.5,\"y\":-60,\"z\":0.5,\"timeoutMs\":20000}");
        assertEquals("stuck", r.get("reason").getAsString(), r.toString());
    }

    @Test
    void moveToTimesOut() throws Exception {
        JsonObject r = act("{\"bot\":\"Act\",\"action\":\"move_to\",\"x\":200.5,\"y\":-60,\"z\":0.5,\"timeoutMs\":1000}");
        assertEquals("timeout", r.get("reason").getAsString(), r.toString());
        assertFalse(r.get("reached").getAsBoolean());
    }

    @Test
    void lookGiveSelectAndState() throws Exception {
        JsonObject look = act("{\"bot\":\"Act\",\"action\":\"look\",\"x\":10.5,\"y\":-58.38,\"z\":0.5}");
        assertEquals(-90.0, look.get("yaw").getAsDouble(), 0.5);
        assertEquals(1, act("{\"bot\":\"Act\",\"action\":\"give\",\"item\":\"diamond_sword\"}").get("given").getAsInt());
        act("{\"bot\":\"Act\",\"action\":\"give\",\"item\":\"stone\",\"count\":10}");
        JsonObject sel = act("{\"bot\":\"Act\",\"action\":\"select_hotbar\",\"slot\":1}");
        assertEquals("minecraft:stone", sel.getAsJsonObject("held").get("id").getAsString());
        JsonObject state = act("{\"bot\":\"Act\",\"action\":\"state\"}");
        assertEquals(1, state.get("heldSlot").getAsInt());
        assertEquals(20.0, state.get("health").getAsDouble(), 0.01);
        assertEquals(2, state.getAsJsonArray("inventory").size());
        assertEquals("INVALID_PARAMS", hub.error("bot.action", "{\"bot\":\"Act\",\"action\":\"give\",\"item\":\"not_an_item\"}").get("code").getAsString());
    }

    @Test
    void deadBotRespawns() throws Exception {
        hub.result("server.command", "{\"command\":\"minecraft:kill Act\"}");
        Thread.sleep(2500);
        JsonObject state = act("{\"bot\":\"Act\",\"action\":\"state\"}");
        assertFalse(state.get("dead").getAsBoolean(), state.toString());
        assertEquals(20.0, state.get("health").getAsDouble(), 0.01);
    }

    @Test
    void unknownBotAndUnknownAction() throws Exception {
        assertEquals("BOT_NOT_FOUND", hub.error("bot.action", "{\"bot\":\"Ghost\",\"action\":\"state\"}").get("code").getAsString());
        assertEquals("INVALID_PARAMS", hub.error("bot.action", "{\"bot\":\"Act\",\"action\":\"fly\"}").get("code").getAsString());
    }
}
```

The `look` case: the bot stands at (0.5, −60, 0.5) with its eyes at −58.38. Looking at a point 10 blocks east at eye height gives yaw −90 and pitch 0.

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:integrationTest --tests '*BotActionsIT*'`
Expected: FAIL with `bot.action failed: … UNKNOWN_METHOD`.

- [ ] **Step 3: Implement**

`BotJson.java`, add:

```java
    public static JsonObject item(org.bukkit.inventory.ItemStack s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.getType().getKey().toString());
        o.addProperty("count", s.getAmount());
        o.addProperty("name", plain(s.effectiveName()));
        java.util.List<net.kyori.adventure.text.Component> lore = s.lore();
        if (lore != null && !lore.isEmpty()) {
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            lore.forEach(c -> a.add(plain(c)));
            o.add("lore", a);
        }
        return o;
    }

    public static com.google.gson.JsonArray messages(java.util.List<BotInbox.Message> list) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (BotInbox.Message m : list) {
            JsonObject o = new JsonObject();
            o.addProperty("time", m.time());
            o.addProperty("kind", m.kind());
            o.addProperty("text", m.text());
            if (m.sender() != null) o.addProperty("sender", m.sender());
            a.add(o);
        }
        return a;
    }

    public static JsonObject state(Bot b) {
        org.bukkit.entity.Player p = b.bukkit();
        JsonObject o = summary(b);
        o.addProperty("yaw", round(p.getLocation().getYaw()));
        o.addProperty("pitch", round(p.getLocation().getPitch()));
        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getFoodLevel());
        o.addProperty("gameMode", p.getGameMode().name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("onGround", p.isOnGround());
        o.addProperty("dead", p.isDead());
        o.addProperty("moving", b.moving());
        o.addProperty("heldSlot", p.getInventory().getHeldItemSlot());
        org.bukkit.inventory.ItemStack held = p.getInventory().getItemInMainHand();
        if (!held.getType().isAir()) o.add("held", item(held));
        com.google.gson.JsonArray inv = new com.google.gson.JsonArray();
        org.bukkit.inventory.ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null || contents[i].getType().isAir()) continue;
            JsonObject it = item(contents[i]);
            it.addProperty("slot", i);
            inv.add(it);
        }
        o.add("inventory", inv);
        var view = p.getOpenInventory();
        boolean menu = view.getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING;
        if (menu) o.addProperty("openMenu", plain(view.title()));
        else o.add("openMenu", com.google.gson.JsonNull.INSTANCE);
        return o;
    }

    static String plain(net.kyori.adventure.text.Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }
```

Before writing, check that `BotJson.summary` exists from Task 3; tidy the fully qualified names into imports when writing the file.

`BotActions.java`:

```java
package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/** The bot.action verbs. Each runs its game work on the server thread through `sync`. */
public final class BotActions {
    private BotActions() {}

    public static CompletableFuture<JsonElement> run(JsonObject p, BotManager bots, Sync sync) {
        String action = Args.string(p, "action");
        String name = Args.string(p, "bot");
        return switch (action) {
            case "chat" -> sync.global(() -> {
                bots.get(name).bukkit().chat(Args.string(p, "text"));
                JsonObject r = new JsonObject();
                r.addProperty("sent", true);
                return (JsonElement) r;
            });
            case "command" -> command(p, bots, sync, name);
            case "messages" -> sync.global(() -> {
                long since = Args.optLong(p, "since").orElse(0L);
                int limit = Math.clamp(Args.optInt(p, "limit").orElse(50), 1, 200);
                JsonObject r = new JsonObject();
                r.add("messages", BotJson.messages(bots.get(name).inbox().since(since, limit)));
                return (JsonElement) r;
            });
            case "look" -> sync.global(() -> look(bots.get(name), p));
            case "move_to" -> sync.global(() -> bots.get(name).moveTo(
                            Args.number(p, "x"), Args.number(p, "y"), Args.number(p, "z"),
                            p.has("tolerance") ? Args.number(p, "tolerance") : 0.5,
                            Args.bool(p, "sprint", false),
                            Math.clamp(Args.optLong(p, "timeoutMs").orElse(10_000L), 500L, 120_000L)))
                    .thenCompose(f -> f).thenApply(r -> (JsonElement) r);
            case "state" -> sync.global(() -> (JsonElement) BotJson.state(bots.get(name)));
            case "give" -> sync.global(() -> give(bots.get(name), p));
            case "select_hotbar" -> sync.global(() -> selectHotbar(bots.get(name), Args.integer(p, "slot")));
            default -> BotGuiActions.run(action, p, bots, sync, name);
        };
    }

    private static CompletableFuture<JsonElement> command(JsonObject p, BotManager bots, Sync sync, String name) {
        String raw = Args.string(p, "command").strip();
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        long collectMs = Math.clamp(Args.optLong(p, "collectMs").orElse(300L), 0L, 5000L);
        long started = System.currentTimeMillis();
        return sync.global(() -> {
                    Bot b = bots.get(name);
                    return new Object[] {b, b.bukkit().performCommand(command)};
                })
                .thenCompose(r -> CompletableFuture.supplyAsync(() -> r, CompletableFuture.delayedExecutor(collectMs, TimeUnit.MILLISECONDS)))
                .thenApply(r -> {
                    Bot b = (Bot) r[0];
                    JsonObject o = new JsonObject();
                    o.addProperty("command", command);
                    o.addProperty("success", (Boolean) r[1]);
                    o.add("messages", BotJson.messages(b.inbox().since(started, 200)));
                    return o;
                });
    }

    private static JsonElement look(Bot b, JsonObject p) {
        ServerPlayer sp = b.player();
        float yaw;
        float pitch;
        if (p.has("x")) {
            var eye = sp.getEyePosition();
            double dx = Args.number(p, "x") - eye.x;
            double dy = Args.number(p, "y") - eye.y;
            double dz = Args.number(p, "z") - eye.z;
            yaw = Steering.yaw(dx, dz);
            pitch = Steering.pitch(dx, dy, dz);
        } else {
            yaw = (float) Args.number(p, "yaw");
            pitch = (float) Math.clamp(Args.number(p, "pitch"), -90, 90);
        }
        face(sp, yaw, pitch);
        JsonObject r = new JsonObject();
        r.addProperty("yaw", BotJson.round(yaw));
        r.addProperty("pitch", BotJson.round(pitch));
        return r;
    }

    static void face(ServerPlayer sp, float yaw, float pitch) {
        sp.setYRot(yaw);
        sp.setXRot(pitch);
        sp.setYHeadRot(yaw);
        sp.setYBodyRot(yaw);
    }

    private static JsonElement give(Bot b, JsonObject p) {
        ItemStack stack;
        try {
            stack = Bukkit.getItemFactory().createItemStack(Args.string(p, "item"));
        } catch (IllegalArgumentException e) {
            throw new AgentError("INVALID_PARAMS", "Unknown item: " + Args.string(p, "item"),
                    "Use an item id like diamond_sword, optionally with components: diamond_sword[enchantments={sharpness:5}].");
        }
        int count = Math.clamp(Args.optInt(p, "count").orElse(1), 1, 2304);
        stack.setAmount(1);
        int given = 0;
        int leftover = 0;
        for (int i = 0; i < count; i++) {
            if (b.bukkit().getInventory().addItem(stack.clone()).isEmpty()) given++;
            else leftover++;
        }
        JsonObject r = new JsonObject();
        r.addProperty("given", given);
        r.addProperty("leftover", leftover);
        return r;
    }

    private static JsonElement selectHotbar(Bot b, int slot) {
        if (slot < 0 || slot > 8) throw Args.invalid("slot must be 0-8");
        // Through the packet handler so PlayerItemHeldEvent fires as for a real client.
        b.listener().handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
        JsonObject r = new JsonObject();
        r.addProperty("slot", b.bukkit().getInventory().getHeldItemSlot());
        ItemStack held = b.bukkit().getInventory().getItemInMainHand();
        if (!held.getType().isAir()) r.add("held", BotJson.item(held));
        return r;
    }
}
```

Task 5 adds `BotGuiActions`. Until then, give it this stub so the switch compiles and unknown verbs fail cleanly:

```java
package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import java.util.concurrent.CompletableFuture;

final class BotGuiActions {
    private BotGuiActions() {}

    static CompletableFuture<JsonElement> run(String action, JsonObject p, BotManager bots, Sync sync, String name) {
        return CompletableFuture.failedFuture(Args.invalid("Unknown bot action: " + action));
    }
}
```

`BotHandler.java`, add:

```java
    static CompletableFuture<JsonElement> action(JsonObject p, CraftwirePlugin self) {
        self.agentConfig().require(self.agentConfig().allowBots(), "allow-bots");
        return com.uxplima.craftwire.paper.bot.BotActions.run(p, self.bots(), self.sync());
    }
```

`Handlers`: `d.register("bot.action", p -> BotHandler.action(p, plugin));`

- [ ] **Step 4: Run the tests**

Run:
```bash
JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test :agent-paper:integrationTest > .superpowers/sdd/2026-10-06-craftwire-m4-bots/t4.log 2>&1; tail -20 .superpowers/sdd/2026-10-06-craftwire-m4-bots/t4.log
```
Expected: `BotActionsIT` 9/9 and all earlier ITs pass.

If `moveToJumpsOneBlockSteps` fails because the bot hits the step mid-jump, debug with superpowers:systematic-debugging, logging `horizontalCollision`/`onGround` per tick. Do not raise the step-test tolerance.

- [ ] **Step 5: Commit**

```bash
git add agent-paper/src
git commit -m "feat(paper): bot actions — chat, command replies, walking, look, give, hotbar, state"
```

---

### Task 5: Bot GUI, use and attack

**Files:**
- Create: `agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/BotGuiIT.java`
- Modify:
  - `bot/BotGuiActions.java` (replace the stub)
  - `bot/BotJson.java` (`gui`)
  - `test-fixtures/src/main/java/com/uxplima/craftwire/fixtures/FixturePlugin.java` (`/cwfixture menu`)

**Interfaces:**
- Consumes: `Bot.listener()`, `Bot.nextSequence()`, `BotActions.face`, `BotJson.item`.
- Produces these verbs:
  - `gui_read` → `{open, title?, type?, slotCount?, slots:[{slot, container: menu|player, id, count, name, lore?}]}`. The shape matches the client `gui_read`.
  - `gui_click {slot, click: left|right|shift = left, settleMs=150}` → `{clicked, gui}`.
  - `gui_close` → `{closed}`.
  - `use {hand: main|off = main, block?:{x,y,z}, face=up, entity?}` → `{used: air|block|entity}`.
  - `attack {entity? | type?}` → `{target:{uuid,type}, healthBefore, healthAfter}`.
- Fixture: `/cwfixture menu` opens "Fixture Menu" (27 slots, diamond in slot 4). A click on slot 4 is cancelled and answered with "fixture: clicked 4"; the menu then closes one tick later.

- [ ] **Step 1: Extend the fixture plugin**

Replace `FixturePlugin.java` with:

```java
package com.uxplima.craftwire.fixtures;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only plugin: predictable behaviour for the Craftwire integration tests to observe. */
public final class FixturePlugin extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equals("menu") && sender instanceof Player player) {
            player.openInventory(new Menu().inventory);
            return true;
        }
        sender.sendMessage(Component.text("fixture: now"));
        // Many plugins answer a tick or more later (async lookups, menus); server_command must still see it.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> sender.sendMessage(Component.text("fixture: later")), 2);
        return true;
    }

    /** Behaves like a typical plugin menu: clicks are cancelled, answered, and the menu closes a tick later. */
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu)) return;
        e.setCancelled(true);
        if (e.getRawSlot() != 4) return;
        Player p = (Player) e.getWhoClicked();
        p.sendMessage(Component.text("fixture: clicked 4"));
        Bukkit.getScheduler().runTask(this, () -> p.closeInventory());
    }

    private static final class Menu implements InventoryHolder {
        final Inventory inventory = Bukkit.createInventory(this, 27, Component.text("Fixture Menu"));

        Menu() {
            inventory.setItem(4, new ItemStack(Material.DIAMOND));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
```

- [ ] **Step 2: Write the failing integration test**

`BotGuiIT.java`:

```java
package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BotGuiIT {
    final ItHub hub = ItEnv.get().hub;

    @BeforeEach
    void spawn() throws Exception {
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":20,\"y\":-60,\"z\":20},\"max\":{\"x\":26,\"y\":-56,\"z\":26},\"block\":\"air\"}");
        hub.result("bot.spawn", "{\"names\":[\"Gui\"],\"location\":{\"x\":22.5,\"y\":-60,\"z\":22.5}}");
        Thread.sleep(300);
    }

    @AfterEach
    void cleanUp() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
        hub.result("server.command", "{\"command\":\"minecraft:kill @e[type=pig]\"}");
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":20,\"y\":-60,\"z\":20},\"max\":{\"x\":26,\"y\":-56,\"z\":26},\"block\":\"air\"}");
    }

    JsonObject act(String json) throws Exception {
        return hub.result("bot.action", json).getAsJsonObject();
    }

    @Test
    void guiReadShowsThePluginMenu() throws Exception {
        assertFalse(act("{\"bot\":\"Gui\",\"action\":\"gui_read\"}").get("open").getAsBoolean());
        act("{\"bot\":\"Gui\",\"action\":\"command\",\"command\":\"cwfixture menu\",\"collectMs\":100}");
        JsonObject gui = act("{\"bot\":\"Gui\",\"action\":\"gui_read\"}");
        assertTrue(gui.get("open").getAsBoolean());
        assertEquals("Fixture Menu", gui.get("title").getAsString());
        JsonObject first = gui.getAsJsonArray("slots").get(0).getAsJsonObject();
        assertEquals(4, first.get("slot").getAsInt());
        assertEquals("minecraft:diamond", first.get("id").getAsString());
        assertEquals("menu", first.get("container").getAsString());
    }

    @Test
    void guiClickSeesThePluginsReaction() throws Exception {
        act("{\"bot\":\"Gui\",\"action\":\"command\",\"command\":\"cwfixture menu\",\"collectMs\":100}");
        JsonObject r = act("{\"bot\":\"Gui\",\"action\":\"gui_click\",\"slot\":4}");
        assertFalse(r.getAsJsonObject("gui").get("open").getAsBoolean(), r.toString()); // the plugin closed it a tick later
        assertTrue(act("{\"bot\":\"Gui\",\"action\":\"messages\"}").toString().contains("fixture: clicked 4"));
        assertEquals("NO_SCREEN_OPEN", hub.error("bot.action", "{\"bot\":\"Gui\",\"action\":\"gui_click\",\"slot\":4}").get("code").getAsString());
    }

    @Test
    void guiCloseAndSlotRange() throws Exception {
        act("{\"bot\":\"Gui\",\"action\":\"command\",\"command\":\"cwfixture menu\",\"collectMs\":100}");
        assertEquals("SLOT_OUT_OF_RANGE", hub.error("bot.action", "{\"bot\":\"Gui\",\"action\":\"gui_click\",\"slot\":999}").get("code").getAsString());
        assertTrue(act("{\"bot\":\"Gui\",\"action\":\"gui_close\"}").get("closed").getAsBoolean());
        assertFalse(act("{\"bot\":\"Gui\",\"action\":\"gui_read\"}").get("open").getAsBoolean());
    }

    @Test
    void useOnABlockPlacesTheHeldBlock() throws Exception {
        act("{\"bot\":\"Gui\",\"action\":\"give\",\"item\":\"stone\",\"count\":4}");
        JsonObject r = act("{\"bot\":\"Gui\",\"action\":\"use\",\"block\":{\"x\":24,\"y\":-61,\"z\":22},\"face\":\"up\"}");
        assertEquals("block", r.get("used").getAsString());
        Thread.sleep(100);
        JsonObject b = hub.result("world.query", "{\"action\":\"block\",\"x\":24,\"y\":-60,\"z\":22}").getAsJsonObject();
        assertEquals("minecraft:stone", b.get("block").getAsString(), b.toString());
        assertEquals("OUT_OF_REACH", hub.error("bot.action", "{\"bot\":\"Gui\",\"action\":\"use\",\"block\":{\"x\":60,\"y\":-61,\"z\":60}}").get("code").getAsString());
    }

    @Test
    void attackDamagesTheNearestEntityOfAType() throws Exception {
        hub.result("server.command", "{\"command\":\"minecraft:summon pig 24.5 -60 22.5 {NoAI:1b}\"}");
        Thread.sleep(200);
        JsonObject r = act("{\"bot\":\"Gui\",\"action\":\"attack\",\"type\":\"pig\"}");
        assertEquals("minecraft:pig", r.getAsJsonObject("target").get("type").getAsString());
        assertTrue(r.get("healthAfter").getAsDouble() < r.get("healthBefore").getAsDouble(), r.toString());
        assertEquals("ENTITY_NOT_FOUND", hub.error("bot.action", "{\"bot\":\"Gui\",\"action\":\"attack\",\"type\":\"cow\"}").get("code").getAsString());
    }
}
```

Before running, check the field name `world.query block` returns for the block id (`block`?) and adapt the assertion to the existing format; `WorldQueryIT` shows it.

- [ ] **Step 3: Run it to verify it fails**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :test-fixtures:build :agent-paper:integrationTest --tests '*BotGuiIT*'`
Expected: FAIL with `INVALID_PARAMS: Unknown bot action: gui_read` (the stub).

- [ ] **Step 4: Implement**

`BotJson.java`, add:

```java
    /** The bot's open menu in the same shape as the client gui_read; open:false when only its own inventory is "open". */
    public static JsonObject gui(Bot b) {
        var view = b.bukkit().getOpenInventory();
        JsonObject o = new JsonObject();
        boolean open = view.getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING;
        o.addProperty("open", open);
        if (!open) return o;
        o.addProperty("title", plain(view.title()));
        o.addProperty("type", view.getType().name());
        int count = view.countSlots();
        o.addProperty("slotCount", count);
        int topSize = view.getTopInventory().getSize();
        com.google.gson.JsonArray slots = new com.google.gson.JsonArray();
        for (int raw = 0; raw < count; raw++) {
            org.bukkit.inventory.ItemStack s = view.getItem(raw);
            if (s == null || s.getType().isAir()) continue;
            JsonObject it = item(s);
            it.addProperty("slot", raw);
            it.addProperty("container", raw < topSize ? "menu" : "player");
            slots.add(it);
        }
        o.add("slots", slots);
        return o;
    }
```

`BotGuiActions.java` (replace the stub):

```java
package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.inventory.InventoryType;

/** GUI, use and attack verbs, sent through the server's own packet handlers so plugins see real-client events. */
final class BotGuiActions {
    private BotGuiActions() {}

    static CompletableFuture<JsonElement> run(String action, JsonObject p, BotManager bots, Sync sync, String name) {
        return switch (action) {
            case "gui_read" -> sync.global(() -> (JsonElement) BotJson.gui(bots.get(name)));
            case "gui_click" -> {
                long settleMs = Math.clamp(Args.optLong(p, "settleMs").orElse(150L), 0L, 5000L);
                yield sync.global(() -> click(bots.get(name), Args.integer(p, "slot"), Args.optString(p, "click").orElse("left")))
                        .thenCompose(v -> CompletableFuture.supplyAsync(() -> v, CompletableFuture.delayedExecutor(settleMs, TimeUnit.MILLISECONDS)))
                        .thenCompose(v -> sync.global(() -> {
                            JsonObject r = new JsonObject();
                            r.addProperty("clicked", v);
                            r.add("gui", BotJson.gui(bots.get(name)));
                            return (JsonElement) r;
                        }));
            }
            case "gui_close" -> sync.global(() -> close(bots.get(name)));
            case "use" -> sync.global(() -> use(bots.get(name), p));
            case "attack" -> sync.global(() -> attack(bots.get(name), p));
            default -> CompletableFuture.failedFuture(Args.invalid("Unknown bot action: " + action));
        };
    }

    private static int click(Bot b, int slot, String click) {
        requireMenu(b);
        AbstractContainerMenu menu = b.player().containerMenu;
        if (slot < 0 || slot >= menu.slots.size()) {
            throw new AgentError("SLOT_OUT_OF_RANGE", "Slot " + slot + " is outside 0.." + (menu.slots.size() - 1),
                    "Use a slot number from gui_read.");
        }
        ContainerInput input = click.equals("shift") ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
        byte button = (byte) (click.equals("right") ? 1 : 0);
        if (!click.equals("left") && !click.equals("right") && !click.equals("shift")) throw Args.invalid("click must be left, right or shift");
        // Empty predicted-change map: the server applies the click and resyncs the (absent) client afterwards.
        b.listener().handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) slot, button, input,
                new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
        return slot;
    }

    private static JsonElement close(Bot b) {
        requireMenu(b);
        b.listener().handleContainerClose(new ServerboundContainerClosePacket(b.player().containerMenu.containerId));
        JsonObject r = new JsonObject();
        r.addProperty("closed", true);
        return r;
    }

    private static void requireMenu(Bot b) {
        if (b.bukkit().getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING) {
            throw new AgentError("NO_SCREEN_OPEN", b.name() + " has no menu open", "Open one first, e.g. bot_action {action:'command'} with the plugin's menu command.");
        }
    }

    private static JsonElement use(Bot b, JsonObject p) {
        ServerPlayer sp = b.player();
        InteractionHand hand = Args.optString(p, "hand").orElse("main").equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        JsonObject r = new JsonObject();
        if (p.has("block")) {
            JsonObject at = Args.object(p, "block");
            BlockPos pos = new BlockPos(Args.integer(at, "x"), Args.integer(at, "y"), Args.integer(at, "z"));
            Direction face = Direction.byName(Args.optString(p, "face").orElse("up").toLowerCase(Locale.ROOT));
            if (face == null) throw Args.invalid("face must be up, down, north, south, east or west");
            Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            reach(sp, hit, sp.blockInteractionRange());
            lookAt(sp, hit);
            b.listener().handleUseItemOn(new ServerboundUseItemOnPacket(hand, new BlockHitResult(hit, face, pos, false), b.nextSequence()));
            r.addProperty("used", "block");
        } else if (p.has("entity")) {
            Entity target = entity(sp, Args.string(p, "entity"));
            Vec3 center = target.position().add(0, target.getBbHeight() / 2, 0);
            reach(sp, center, sp.entityInteractionRange());
            lookAt(sp, center);
            b.listener().handleInteract(new ServerboundInteractPacket(target.getId(), hand, new Vec3(0, target.getBbHeight() / 2, 0), false));
            r.addProperty("used", "entity");
        } else {
            b.listener().handleUseItem(new ServerboundUseItemPacket(hand, b.nextSequence(), sp.getYRot(), sp.getXRot()));
            r.addProperty("used", "air");
        }
        b.listener().handleAnimate(new ServerboundSwingPacket(hand));
        return r;
    }

    private static JsonElement attack(Bot b, JsonObject p) {
        ServerPlayer sp = b.player();
        Entity target;
        if (p.has("entity")) {
            target = entity(sp, Args.string(p, "entity"));
        } else {
            String type = Args.string(p, "type").toLowerCase(Locale.ROOT);
            String id = type.contains(":") ? type : "minecraft:" + type;
            double range = sp.entityInteractionRange();
            target = sp.level().getEntities(sp, sp.getBoundingBox().inflate(range),
                            e -> net.minecraft.world.entity.EntityType.getKey(e.getType()).toString().equals(id))
                    .stream().min(Comparator.comparingDouble(sp::distanceToSqr))
                    .orElseThrow(() -> new AgentError("ENTITY_NOT_FOUND", "No " + id + " within reach of " + b.name(),
                            "move_to the target first, or pass entity (a UUID from world_query entities)."));
        }
        Vec3 center = target.position().add(0, target.getBbHeight() / 2, 0);
        reach(sp, center, sp.entityInteractionRange());
        lookAt(sp, center);
        double before = target instanceof LivingEntity l ? l.getHealth() : 0;
        b.listener().handleAttack(new ServerboundAttackPacket(target.getId()));
        b.listener().handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        JsonObject t = new JsonObject();
        t.addProperty("uuid", target.getUUID().toString());
        t.addProperty("type", net.minecraft.world.entity.EntityType.getKey(target.getType()).toString());
        JsonObject r = new JsonObject();
        r.add("target", t);
        if (target instanceof LivingEntity l) {
            r.addProperty("healthBefore", before);
            r.addProperty("healthAfter", l.getHealth());
        }
        return r;
    }

    private static Entity entity(ServerPlayer sp, String uuid) {
        Entity e;
        try {
            e = sp.level().getEntity(UUID.fromString(uuid));
        } catch (IllegalArgumentException bad) {
            throw Args.invalid("entity must be a UUID");
        }
        if (e == null) throw new AgentError("ENTITY_NOT_FOUND", "No entity " + uuid + " in " + sp.level().dimension().identifier(), "Use world_query {action:'entities'} for UUIDs.");
        return e;
    }

    private static void reach(ServerPlayer sp, Vec3 point, double range) {
        double d = sp.getEyePosition().distanceTo(point);
        if (d > range + 0.5) {
            throw new AgentError("OUT_OF_REACH", String.format(Locale.ROOT, "Target is %.1f blocks away; reach is %.1f", d, range),
                    "move_to closer first.");
        }
    }

    private static void lookAt(ServerPlayer sp, Vec3 point) {
        Vec3 eye = sp.getEyePosition();
        BotActions.face(sp, Steering.yaw(point.x - eye.x, point.z - eye.z), Steering.pitch(point.x - eye.x, point.y - eye.y, point.z - eye.z));
    }
}
```

Compile notes for the executor: `sp.level()` returns `ServerLevel` in 26.2, and `dimension().identifier()` may be named `location()`. Check with javap and fix, ledgering the ruling. `Direction.byName` may return an Optional or nullable value; adapt.

- [ ] **Step 5: Run the tests**

Run:
```bash
JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :test-fixtures:build :agent-paper:test :agent-paper:integrationTest > .superpowers/sdd/2026-10-06-craftwire-m4-bots/t5.log 2>&1; tail -20 .superpowers/sdd/2026-10-06-craftwire-m4-bots/t5.log
```
Expected: `BotGuiIT` 5/5 and all other ITs pass. `CommandIT` still sees "fixture: now"/"fixture: later" for plain `/cwfixture`.

- [ ] **Step 6: Commit**

```bash
git add agent-paper/src test-fixtures/src
git commit -m "feat(paper): bots read and click plugin GUIs, use items and blocks, attack"
```

---

### Task 6: Hub tools — `bot_spawn`, `bot_remove`, `bot_action`

**Files:**
- Create: `hub/src/tools/bot-tools.ts`, `hub/test/bot-tools.test.ts`
- Modify: `hub/src/server.ts`, `hub/test/cli.test.ts`

**Interfaces:**
- Consumes: `forward`, `targetArgs`, `defineTool`, `ok` from `registry.ts`; the RPC methods from Tasks 3–5.
- Produces three MCP tools. `bot_action`'s request timeout follows the verb:
  - `move_to`: timeoutMs (default 10000) + 5000;
  - `command`: collectMs + 10000;
  - `gui_click`: settleMs + 10000;
  - everything else: 15000.

- [ ] **Step 1: Write the failing tests**

`hub/test/bot-tools.test.ts`:

```ts
import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => {
  await hub?.close();
  hub = undefined;
});

async function withServerAgent() {
  hub = await startHub();
  const agent = await connectFakeAgent(hub.port, { token: hub.token, kind: "server", name: "srv" });
  return { hub, agent };
}

describe("bot tools", () => {
  it("bot_spawn forwards count, prefix and location with defaults", async () => {
    const { hub, agent } = await withServerAgent();
    let seen: Record<string, unknown> = {};
    agent.onRequest("bot.spawn", (p) => { seen = p; return { bots: [{ name: "Bot1" }] }; });
    const r = json(await hub.call("bot_spawn", { location: { x: 1, y: 64, z: 2 } }));
    expect(r.bots[0].name).toBe("Bot1");
    expect(seen).toMatchObject({ count: 1, namePrefix: "Bot", location: { x: 1, y: 64, z: 2 } });
  });

  it("bot_spawn rejects invalid names before reaching the server", async () => {
    const { hub } = await withServerAgent();
    const r = await hub.call("bot_spawn", { names: ["no spaces"] });
    expect(r.isError).toBe(true);
  });

  it("bot_remove forwards a name or all", async () => {
    const { hub, agent } = await withServerAgent();
    const calls: Record<string, unknown>[] = [];
    agent.onRequest("bot.remove", (p) => { calls.push(p); return { removed: [] }; });
    await hub.call("bot_remove", { name: "Bot1" });
    await hub.call("bot_remove", { all: true });
    expect(calls).toEqual([{ name: "Bot1", all: false }, { all: true }]);
  });

  it("bot_action waits as long as a move_to may take", async () => {
    const { hub, agent } = await withServerAgent(); // startHub's default request timeout is 2000 ms
    agent.onRequest("bot.action", async (p) => {
      await new Promise((r) => setTimeout(r, 2500));
      return { reached: true, action: p.action };
    });
    const r = json(await hub.call("bot_action", { bot: "Bot1", action: "move_to", x: 1, y: 2, z: 3, timeoutMs: 4000 }));
    expect(r).toEqual({ reached: true, action: "move_to" });
  });

  it("bot_action passes structured errors through", async () => {
    const { hub, agent } = await withServerAgent();
    agent.onRequest("bot.action", () => { throw Object.assign(new Error("No bot named Ghost"), { code: "BOT_NOT_FOUND", hint: "bot_spawn creates bots" }); });
    expect(json(await hub.call("bot_action", { bot: "Ghost", action: "state" }))).toMatchObject({ code: "BOT_NOT_FOUND" });
  });
});
```

In `hub/test/cli.test.ts`, add `"bot_action", "bot_remove", "bot_spawn"` at the start of the expected sorted list (23 tools) and rename the test to "exposes all M1–M4 tools".

- [ ] **Step 2: Run them to verify they fail**

Run: `cd hub && npx vitest run test/bot-tools.test.ts test/cli.test.ts`
Expected: FAIL (tool not found; the cli list lacks the bot tools).

- [ ] **Step 3: Implement**

`hub/src/tools/bot-tools.ts`:

```ts
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { defineTool, forward, ok, targetArgs, type ToolContext } from "./registry.js";

const NAME = /^[A-Za-z0-9_]{3,16}$/;
const ACTIONS = [
  "chat", "command", "messages", "look", "move_to", "state", "give", "select_hotbar",
  "gui_read", "gui_click", "gui_close", "use", "attack",
] as const;

function actionTimeout(a: { action: string; timeoutMs?: number; collectMs?: number; settleMs?: number }): number {
  switch (a.action) {
    case "move_to": return (a.timeoutMs ?? 10_000) + 5000;
    case "command": return (a.collectMs ?? 300) + 10_000;
    case "gui_click": return (a.settleMs ?? 150) + 10_000;
    default: return 15_000;
  }
}

export function registerBotTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "bot_spawn",
    "Spawn server-side fake players (bots) on a Paper server running the Craftwire plugin. They join like real players (join event, tab list) and plugins treat them as players. Names: `names`, or `count` × namePrefix1, namePrefix2, …. location defaults to the main world's spawn. Returns each bot's name and position.",
    {
      ...targetArgs,
      count: z.number().int().min(1).max(20).default(1),
      namePrefix: z.string().regex(/^[A-Za-z0-9_]{1,13}$/).default("Bot"),
      names: z.array(z.string().regex(NAME)).min(1).max(20).optional(),
      location: z.object({
        world: z.string().optional(), x: z.number(), y: z.number(), z: z.number(),
        yaw: z.number().optional(), pitch: z.number().optional(),
      }).optional(),
    },
    async (args, c) => ok(await forward(c, "server", "bot.spawn", args, 30_000)));

  defineTool(server, ctx, "bot_remove",
    "Remove a bot (`name`) or every bot (`all:true`). Bots leave like players logging out (quit event, data saved).",
    { ...targetArgs, name: z.string().optional(), all: z.boolean().default(false) },
    async (args, c) => ok(await forward(c, "server", "bot.remove", args.all ? { ...args, name: undefined } : args)));

  defineTool(server, ctx, "bot_action",
    "Drive a bot. chat {text}. command {command, collectMs} returns the messages the bot received. messages {since?, limit?}. look {yaw,pitch} or {x,y,z}. move_to {x,y,z, tolerance?, sprint?, timeoutMs?} walks in a straight line (jumps 1-block steps) and returns reached + reason (arrived/stuck/timeout/died). state: position, health, held item, inventory, open menu. give {item, count} (ids like diamond_sword, components allowed). select_hotbar {slot 0-8}. gui_read / gui_click {slot, click: left|right|shift} / gui_close for plugin menus (slots as in gui_read; gui_click returns the menu after the plugin reacted). use: right-click with the held item — {block:{x,y,z}, face} on a block, {entity: uuid} on an entity, or nothing for the air. attack {entity: uuid} or {type: 'zombie'} (nearest within reach). Actions go through the server's packet handlers, so plugins see the same events as from a real client.",
    {
      ...targetArgs,
      bot: z.string().min(1),
      action: z.enum(ACTIONS),
      text: z.string().max(256).optional(),
      command: z.string().max(32_000).optional(),
      collectMs: z.number().int().min(0).max(5000).optional(),
      since: z.number().optional(),
      limit: z.number().int().min(1).max(200).optional(),
      x: z.number().optional(), y: z.number().optional(), z: z.number().optional(),
      yaw: z.number().optional(), pitch: z.number().optional(),
      tolerance: z.number().min(0.1).max(5).optional(),
      sprint: z.boolean().optional(),
      timeoutMs: z.number().int().min(500).max(120_000).optional(),
      item: z.string().optional(),
      count: z.number().int().min(1).max(2304).optional(),
      slot: z.number().int().min(0).optional(),
      click: z.enum(["left", "right", "shift"]).optional(),
      settleMs: z.number().int().min(0).max(5000).optional(),
      hand: z.enum(["main", "off"]).optional(),
      block: z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() }).optional(),
      face: z.enum(["up", "down", "north", "south", "east", "west"]).optional(),
      entity: z.string().optional(),
      type: z.string().optional(),
    },
    async (args, c) => ok(await forward(c, "server", "bot.action", args, actionTimeout(args))));
}
```

In the `bot_remove` handler, the `name: undefined` spread drops the key when serialised (JSON.stringify omits undefined). That matches the test's expected `{ all: true }`.

`hub/src/server.ts`:
- Import and call `registerBotTools(server, ctx)` after `registerDevTools`.
- Add to INSTRUCTIONS (before "After an action…"):

```ts
  "Bots: bot_spawn puts fake players on a Paper server; bot_action drives them (chat, command, move_to, gui_read/gui_click, use, attack, …) so you can test plugins without a real client; bot_remove when done.",
```

- [ ] **Step 4: Run the hub suite**

Run: `cd hub && npx vitest run && npm run typecheck`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add hub/src/tools/bot-tools.ts hub/src/server.ts hub/test/bot-tools.test.ts hub/test/cli.test.ts
git commit -m "feat(hub): bot_spawn, bot_remove and bot_action tools"
```

---

### Task 7: E2E, docs, skills, version 0.4.0

**Files:**
- Modify:
  - `hub/test-e2e/dev-loop.e2e.test.ts` (bot test before the stop test)
  - `README.md`, `protocol/PROTOCOL.md`
  - `claude-plugin/skills/craftwire/SKILL.md`, `claude-plugin/skills/paper-plugin-dev/SKILL.md`
  - `agent-paper/src/main/resources/config.yml` (comment)
  - version files (0.3.0 → 0.4.0): `gradle.properties`, `hub/package.json` + lock, `hub/src/version.ts`, `claude-plugin/.claude-plugin/plugin.json`, `claude-plugin/.mcp.json`, `.claude-plugin/marketplace.json`, README npx line

**Interfaces:**
- Consumes: the tools from Task 6 and the fixture menu from Task 5.

- [ ] **Step 1: Add the failing E2E test**

In `hub/test-e2e/dev-loop.e2e.test.ts`, insert before `it("stops the server gracefully"…`:

```ts
  it("drives a bot through a plugin menu", async () => {
    const spawned = json(await hub.call("bot_spawn", { names: ["E2eBot"] }));
    expect(spawned.bots[0].name).toBe("E2eBot");
    await hub.call("bot_action", { bot: "E2eBot", action: "command", command: "cwfixture menu", collectMs: 100 });
    const gui = json(await hub.call("bot_action", { bot: "E2eBot", action: "gui_read" }));
    expect(gui.title).toBe("Fixture Menu");
    const click = json(await hub.call("bot_action", { bot: "E2eBot", action: "gui_click", slot: 4 }));
    expect(click.gui.open).toBe(false);
    const inbox = json(await hub.call("bot_action", { bot: "E2eBot", action: "messages" }));
    expect(JSON.stringify(inbox)).toContain("fixture: clicked 4");
    expect(json(await hub.call("bot_remove", { all: true })).removed).toEqual(["E2eBot"]);
  });
```

Run:
```bash
JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:build :test-fixtures:build
cd hub && CRAFTWIRE_E2E_JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" npm run test:e2e
```
Expected: 5/5 PASS. The plugin and the tools already exist, so this test passes at once; it is a cross-layer check, not TDD of new code. Ledger that it passed immediately.

- [ ] **Step 2: Docs and skills**

`README.md`:
- Intro: replace "Bots follow in the next milestone." with "Server-side bots stand in for players when you test plugins."
- Add a section after "Dev loop":

```markdown
## Bots

`bot_spawn` puts fake players on a Paper server running the Craftwire plugin. They join like real players, so plugins see join, chat, command, click and damage events from them.

`bot_action` makes a bot chat, run commands (and returns the replies it received), walk to a point, look, use items and blocks, attack, and read and click plugin menus. `bot_remove` logs them out.

Ask Claude: *"spawn two bots, have one open /shop and buy the first item, and tell me what the plugin answered"*.
```

- Tools list: add `- Bots (M4): \`bot_spawn\` · \`bot_action\` · \`bot_remove\``.
- npx line → `craftwire@0.4.0`.

`protocol/PROTOCOL.md`, append:

```markdown
Server methods (M4, bots; `allow-bots` gates all three):
- `bot.spawn` `{count?, namePrefix?, names?, location?: {world?, x, y, z, yaw?, pitch?}}` → `{bots: [{name, uuid, world, x, y, z}]}`
- `bot.remove` `{name}` or `{all: true}` → `{removed: [names]}`
- `bot.action` `{bot, action, …}`. Actions: chat, command, messages, look, move_to, state, give, select_hotbar, gui_read, gui_click, gui_close, use, attack (parameters as in the `bot_action` tool).
`world.query players` rows carry `bot: true` for bots.
```

`claude-plugin/skills/craftwire/SKILL.md`:
- Description tool list: add `bot_spawn, bot_action`.
- Section before "## Errors":

```markdown
## Bots (server-side fake players)
- `bot_spawn {count}` (or `names`), then drive with `bot_action`. Bots are real players to plugins: permissions, join/quit, chat and click events all fire.
- Menus: `bot_action {action:"command", command:"shop"}` → `gui_read` → `gui_click {slot}`; the click result already includes the menu after the plugin reacted. Read replies with `messages`.
- Walking is straight-line (`move_to`): it hops one-block steps, and reports `stuck` at walls. Give waypoints for longer routes, or `server_command "minecraft:tp Bot1 x y z"`.
- Always `bot_remove {all:true}` when done.
```

`claude-plugin/skills/paper-plugin-dev/SKILL.md`, under "The loop" step 3, add:

```markdown
   - No client needed for most checks: `bot_spawn`, then `bot_action` to run the plugin's commands, click its menus (`gui_read`/`gui_click`), use items or blocks, and read what the plugin sent (`messages`). Remove bots afterwards.
```

`agent-paper/src/main/resources/config.yml`: change `# bot_spawn / bot_action (milestone M4).` to `# bot_spawn / bot_action / bot_remove: server-side fake players.`

- [ ] **Step 3: Bump to 0.4.0**

Read each file first, then replace `0.3.0` with `0.4.0` in:
- `gradle.properties` (`craftwire_version`)
- `hub/src/version.ts`
- `claude-plugin/.claude-plugin/plugin.json`
- `claude-plugin/.mcp.json`
- `.claude-plugin/marketplace.json`
- `README.md`

Then run `cd hub && npm version 0.4.0 --no-git-tag-version`. Verify UTF-8 as in M3 (`iconv -f utf-8 -t utf-8` over the changed files, expect no `BAD`).

- [ ] **Step 4: Run every suite**

Run:
```bash
cd hub && npx vitest run && npm run typecheck
cd .. && JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-core:test :agent-fabric:test :agent-fabric:build :agent-paper:test :agent-paper:build :test-fixtures:build :agent-paper:integrationTest > .superpowers/sdd/2026-10-06-craftwire-m4-bots/all.log 2>&1; tail -5 .superpowers/sdd/2026-10-06-craftwire-m4-bots/all.log
cd hub && CRAFTWIRE_E2E_JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" npm run test:e2e
```
Expected:
- the hub suite passes;
- `BUILD SUCCESSFUL` with 44 + 18 ITs;
- E2E 5/5.

- [ ] **Step 5: Commit**

```bash
git add -A hub/test-e2e README.md protocol/PROTOCOL.md claude-plugin agent-paper/src/main/resources/config.yml gradle.properties hub/package.json hub/package-lock.json hub/src/version.ts .claude-plugin
git commit -m "docs: bots, skills, version 0.4.0"
```

---

### Task 8: Manual acceptance on the user's server (ask first)

These steps change the user's real server: they install plugin 0.4.0, restart the server, and put bots on it. **Ask the user before Step 2.**

**Files:**
- Create: `docs/acceptance/m4-bots.md`

- [ ] **Step 1: Prepare (no side effects)**

Build the hub (`cd hub && npm run build`) and the plugin jar (`craftwire-paper-0.4.0.jar`). Restart the scratch e2e driver on the new hub.

Stop servers with `server_process stop` before ever killing the driver: a tree kill skips the graceful stop.

- [ ] **Step 2: Ask the user (Turkish)**

Explain the run:
1. Start the user's server through `server_process start`.
2. `plugin_deploy {jar: craftwire-paper-0.4.0.jar}`: 0.3.0 goes to `.craftwire-backup/`.
3. Spawn 2 bots.
4. Have a bot open the uxmBuilders menu (find its command with `plugin_manage info uxmBuilders`), `gui_read` it and click a harmless entry.
5. `use` and `attack` nothing important.
6. Remove the bots, then stop the server.

If the user wants to watch, they can join with their client (mod 0.3.0, compatible with protocol 1). Wait for consent.

- [ ] **Step 3: Run it**

Through the driver:
- `server_process start`;
- `plugin_deploy {jar}` → `loaded.version 0.4.0`;
- `bot_spawn {count:2}`;
- `bot_action command` for the Builders menu → `gui_read` → record the slots;
- `gui_click` a navigation slot → record the plugin's reaction;
- `bot_action move_to` a few blocks;
- `messages`;
- `bot_remove {all:true}`;
- `server_process stop`.

If the user's client is connected, take one `screenshot` showing the bots.

- [ ] **Step 4: Record and commit**

Write `docs/acceptance/m4-bots.md` with a call/outcome table and notes, like `m3-dev-loop.md`. Then:

```bash
git add docs/acceptance/m4-bots.md
git commit -m "docs: M4 manual bot run on the user's server"
```

---

## Self-review notes

- **Spec coverage:**
  - `bot_spawn {count, namePrefix, location}`: Tasks 3 and 6.
  - `bot_remove {name | all}`: Tasks 3 and 6.
  - `bot_action` verbs from §4: chat, command, move_to, look and give/select_hotbar in Task 4; use, attack, gui_read and gui_click in Task 5.
  - `allow-bots` → `PERMISSION_DISABLED`: every handler calls `require`.
  - Isolation in one package: Global Constraints and Task 1.
  - ITs against a real server: Tasks 3–5.
- **Additions:** `state`, `messages` and `gui_close` verbs, `names` on spawn, and `bot` on `world_query players`. Each closes a gap a tester hits at once: what the bot holds, what the plugin replied, how to leave a menu, choosing names, and telling bots from players.
- **Not in scope:** pathfinding (`move_to` is straight-line, documented) and block breaking (use `world_edit`, or `use` with a tool). Both are noted in the tool description and the skill.
- **Checked against helpers:** `ItHub.result/error/awaitEvent`, `connectFakeAgent(...).onRequest` (thrown `code`/`hint` become structured errors) and `startHub` (2000 ms request timeout) exist as the tests use them.
