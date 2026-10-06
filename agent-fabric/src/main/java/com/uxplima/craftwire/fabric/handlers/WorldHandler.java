package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.Params;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;

/**
 * world.open {name, create?}: opens a singleplayer world from the title screen, or creates it the way the Create World
 * screen does. Answers before loading starts (NOT_READY while the client is still starting); the hub then waits until the
 * player is in the world.
 */
final class WorldHandler {
    private WorldHandler() {}

    static JsonElement open(JsonObject p) {
        Minecraft mc = Minecraft.getInstance();
        String name = Params.optString(p, "name").orElseThrow(() -> Params.invalid("name is required"));
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        IntegratedServer running = mc.getSingleplayerServer();
        if (running != null && running.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString().equals(name)) {
            o.addProperty("opening", true);   // asked again (e.g. after a lost reply): this world is already on its way
            return o;
        }
        if (mc.level != null || running != null) {
            throw new AgentError("ALREADY_IN_WORLD", "The client is already in another world", "Stop the client and start it again with the world to open.");
        }
        if (mc.gui.overlay() != null || !(mc.gui.screen() instanceof TitleScreen)) {
            throw new AgentError("NOT_READY", "The client is still starting", "Retry in a moment.");
        }
        boolean exists = mc.getLevelSource().levelExists(name);
        if (!p.has("create") || !p.get("create").isJsonObject()) {
            if (!exists) throw new AgentError("WORLD_NOT_FOUND", "No singleplayer world named " + name, "Pass create to make it.");
            // Next task: the reply goes out before loading takes over the client thread.
            mc.execute(() -> mc.createWorldOpenFlows().openWorld(name, () -> mc.gui.setScreen(new TitleScreen())));
            o.addProperty("created", false);
            return o;
        }
        if (exists) throw new AgentError("WORLD_EXISTS", "A world named " + name + " already exists", "Leave out create to open it, or pass create.replace to start over.");
        if (!mc.getLevelSource().isNewLevelIdAcceptable(name)) {
            throw new AgentError("INVALID_PARAMS", "\"" + name + "\" cannot be a world folder name", "Use letters, digits, spaces, - and _.");
        }
        JsonObject c = p.getAsJsonObject("create");
        String type = Params.optString(c, "type").orElse("flat").toLowerCase(Locale.ROOT);
        GameType mode = GameType.byName(Params.optString(c, "gameMode").orElse("creative").toLowerCase(Locale.ROOT), null);
        if (mode == null) throw new AgentError("INVALID_PARAMS", "gameMode must be survival, creative, adventure or spectator", null);
        Difficulty difficulty = Difficulty.byName(Params.optString(c, "difficulty").orElse(type.equals("normal") ? "normal" : "peaceful").toLowerCase(Locale.ROOT));
        if (difficulty == null) throw new AgentError("INVALID_PARAMS", "difficulty must be peaceful, easy, normal or hard", null);
        boolean cheats = Params.optBool(c, "cheats").orElse(true);
        long seed = seed(Params.optString(c, "seed").orElse(""));

        Function<HolderLookup.Provider, WorldDimensions> dimensions = switch (type) {
            case "normal" -> WorldPresets::createNormalWorldDimensions;
            case "flat" -> provider -> provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
            case "void" -> provider -> provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions()
                    .replaceOverworldGenerator(provider, new FlatLevelSource(
                            provider.lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET).getOrThrow(FlatLevelGeneratorPresets.THE_VOID).value().settings()));
            default -> throw new AgentError("INVALID_PARAMS", "type must be normal, flat or void", null);
        };
        LevelSettings settings = new LevelSettings(name, mode, new LevelSettings.DifficultySettings(difficulty, false, false), cheats, WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(seed, type.equals("normal"), false);
        mc.execute(() -> mc.createWorldOpenFlows().createFreshLevel(name, settings, options, dimensions, new TitleScreen()));
        o.addProperty("created", true);
        o.addProperty("type", type);
        o.addProperty("seed", seed);
        return o;
    }

    /** Like the Create World screen: a number is the seed, other text is hashed, empty is random. */
    static long seed(String text) {
        String t = text.trim();
        if (t.isEmpty()) return WorldOptions.randomSeed();
        OptionalLong n = parseLong(t);
        return n.isPresent() ? n.getAsLong() : t.hashCode();
    }

    private static OptionalLong parseLong(String t) {
        try {
            return OptionalLong.of(Long.parseLong(t));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
