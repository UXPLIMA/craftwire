package com.uxplima.craftwire.paper.bot;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.uxplima.craftwire.bot.model.ClientSettings;
import com.uxplima.craftwire.bot.model.Profile;
import com.uxplima.craftwire.paper.Sync;
import com.uxplima.craftwire.paper.compat.ServerCompat;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

/**
 * Joins one bot like a client would (join event, tab list, player data), then applies its profile. Runs on the
 * thread that owns the join location, with its chunk loaded.
 */
final class BotJoin {
    /** The light profile's distances: what the 2026-11-03 measurement used (MSPT 32.6 to 12.8 for 50 spread bots). */
    static final int LIGHT_VIEW = 3;
    static final int LIGHT_SIMULATION = 2;
    static final int LIGHT_SEND = 2;

    private BotJoin() {}

    static Bot join(JoinSpec spec, Location at, BotServices svc) {
        MinecraftServer server = MinecraftServer.getServer();
        ServerLevel level = ((CraftWorld) at.getWorld()).getHandle();
        // Offline-mode UUID: never the same as a real Mojang account's UUID.
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + spec.name()).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = profile(uuid, spec.name(), spec.settings());
        BotLink link = new BotLink(spec.profile() == Profile.REALISTIC, new BotPacks(spec.packs(), spec.verifyPacks(), svc.packs()));
        BotConnection connection = BotConnection.create(link, spec.name());
        ServerPlayer player = new ServerPlayer(server, level, profile, information(spec.settings(), spec.profile()));
        // Placed where it should stand before joining: Folia allows no synchronous teleport afterwards.
        player.snapTo(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        player.setYHeadRot(at.getYaw());
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        FoliaConnections.detach(player, connection);
        Bot bot = new Bot(spec, uuid, connection, player.connection, link, svc);
        link.packAnswers().sink(player.connection::handleResourcePackResponse);
        applySettings(bot, spec.settings());
        if (spec.profile() == Profile.LIGHT) light(bot);
        if (spec.hidden()) bot.bukkit().setVisibleByDefault(false);
        // Paper: a teleport after joining, as before Folia support (26.3 leaves a player that only joined in the
        // air until it moves); Folia allows no synchronous teleport, its bot starts where it was placed.
        if (!Sync.folia()) bot.bukkit().teleport(at);
        bot.settle();
        return bot;
    }

    /** Brand and simulated latency; the client information itself is set at join or by {@link Bot#settings}. */
    static void applySettings(Bot bot, ClientSettings s) {
        // Paper keeps the minecraft:brand payload's value here (Player#getClientBrandName reads it).
        bot.listener().clientBrand = s.brand();
        if (s.pingMs() > 0) ServerCompat.get().setLatency(bot.listener(), s.pingMs());
    }

    /**
     * Small distances, no mob spawning around the bot, and no advancement criteria checks: the per-bot world and
     * advancement work is what made spread-out bots expensive.
     */
    private static void light(Bot bot) {
        Player p = bot.bukkit();
        p.setViewDistance(LIGHT_VIEW);
        p.setSimulationDistance(LIGHT_SIMULATION);
        p.setSendViewDistance(LIGHT_SEND);
        p.setAffectsSpawning(false);
        bot.player().getAdvancements().clearTriggers();
    }

    static ClientInformation information(ClientSettings s, Profile profile) {
        int view = s.viewDistance() > 0 ? s.viewDistance() : profile == Profile.LIGHT ? LIGHT_VIEW : 10;
        ChatVisiblity chat = switch (s.chatVisibility()) {
            case "system" -> ChatVisiblity.SYSTEM;
            case "hidden" -> ChatVisiblity.HIDDEN;
            default -> ChatVisiblity.FULL;
        };
        HumanoidArm hand = s.mainHand().equals("left") ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        return new ClientInformation(s.locale(), view, chat, true, s.skinParts(), hand, false, true, ParticleStatus.ALL);
    }

    /** An offline profile; with a skin URL, an unsigned textures property (offline-mode servers and clients show it). */
    private static GameProfile profile(UUID uuid, String name, ClientSettings s) {
        if (s.skinUrl() == null) return new GameProfile(uuid, name);
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + s.skinUrl().replace("\"", "") + "\"}}}";
        String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return new GameProfile(uuid, name, new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", value))));
    }
}
