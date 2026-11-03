package com.uxplima.craftwire.botrunner.javabot.bot;

import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.botrunner.javabot.world.JavaBlocks;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Applies what the server sends to the bot's client state, packet by packet, like ClientPacketListener: the login
 * and respawns, teleports (confirmed as a client confirms them), health, inventory and menus, chunks and blocks,
 * entities, messages, resource packs, HUD and the special screens. Packets the bot has no use for are dropped.
 * Runner loop thread.
 */
final class JavaPlay {
    /**
     * What the bot asks for in ServerboundChunkBatchReceivedPacket. The vanilla client measures its own decode speed;
     * a bot that decodes only chunks nobody else holds (or none at all) can take them as fast as the server sends.
     */
    private static final float CHUNKS_PER_TICK = 25f;

    private final JavaBot bot;

    JavaPlay(JavaBot bot) {
        this.bot = bot;
    }

    void apply(Packet<?> packet, long now) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) apply(sub, now);
            return;
        }
        boolean tab = packet instanceof ClientboundPlayerInfoUpdatePacket || packet instanceof ClientboundPlayerInfoRemovePacket;
        try {
            if (!tab || bot.tabWatched) bot.hud.accept(packet, now);
        } catch (RuntimeException e) {
            // A packet the HUD copy cannot apply (odd plugin ordering) must never break the bot.
        }
        if (!life(packet) && !items(packet) && !world(packet) && !talk(packet, now)) screens(packet);
    }

    private boolean life(Packet<?> packet) {
        switch (packet) {
            case ClientboundLoginPacket p -> {
                bot.life.login(p.playerId(), p.commonPlayerSpawnInfo().gameType());
                enter(p.commonPlayerSpawnInfo());
            }
            case ClientboundRespawnPacket p -> {
                bot.life.respawned(p.commonPlayerSpawnInfo().gameType());
                enter(p.commonPlayerSpawnInfo());
            }
            case ClientboundPlayerPositionPacket p -> teleport(p);
            case ClientboundSetHealthPacket p -> bot.life.health(p.getHealth(), p.getFood());
            case ClientboundPlayerCombatKillPacket p -> {
                if (p.playerId() == bot.life.entityId()) bot.life.died();
            }
            case ClientboundGameEventPacket p -> {
                if (p.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE) mode(GameType.byId((int) p.getParam()));
            }
            case ClientboundSetEntityMotionPacket p -> {
                if (p.id() == bot.life.entityId()) bot.body.motion(p.movement().x, p.movement().y, p.movement().z);
            }
            case ClientboundSetHeldSlotPacket p -> bot.life.heldSlot(p.slot());
            case ClientboundStartConfigurationPacket p -> {
                // A proxy switch or a data pack reload: the next login describes the world again.
                bot.world.leave();
                bot.inventory.closed();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void enter(CommonPlayerSpawnInfo info) {
        mode(info.gameType());
        bot.world.enter(info.dimension().identifier().toString(), info.dimensionType().value(), bot.registries);
        bot.inventory.closed();
        bot.screens.reset();
    }

    private void mode(GameType mode) {
        bot.life.gameMode(mode);
        bot.creative = mode == GameType.CREATIVE;
    }

    /**
     * Moves the body where the server says and confirms it the way the client does: the accept-teleport packet,
     * then a full position and rotation packet. The first one completes the join.
     */
    private void teleport(ClientboundPlayerPositionPacket p) {
        JavaPhysics b = bot.body;
        Position at = b.position();
        PositionMoveRotation now = new PositionMoveRotation(new Vec3(at.x(), at.y(), at.z()), Vec3.ZERO, b.yaw(), b.pitch());
        PositionMoveRotation to = PositionMoveRotation.calculateAbsolute(now, p.change(), p.relatives());
        Vec3 pos = to.position();
        b.teleport(pos.x, pos.y, pos.z, to.yRot(), to.xRot());
        bot.send(bot.ctx.compat().acceptTeleport(p.id(), pos.x, pos.y, pos.z, to.yRot(), to.xRot()));
        bot.send(new ServerboundMovePlayerPacket.PosRot(pos.x, pos.y, pos.z, to.yRot(), to.xRot(), false, false));
        bot.moves.sent(pos.x, pos.y, pos.z, to.yRot(), to.xRot());
        if (bot.life.positioned()) bot.spawned();
    }

    private boolean items(Packet<?> packet) {
        JavaInventory inv = bot.inventory;
        switch (packet) {
            case ClientboundContainerSetContentPacket p -> inv.content(p);
            case ClientboundContainerSetSlotPacket p -> inv.slot(p);
            case ClientboundSetCursorItemPacket p -> inv.cursor(p.contents());
            case ClientboundSetPlayerInventoryPacket p -> inv.inventorySlot(p.slot(), p.contents());
            case ClientboundContainerSetDataPacket p -> inv.data(p.getContainerId(), p.getId(), p.getValue());
            case ClientboundMerchantOffersPacket p -> inv.offers(p.getContainerId(), p.getOffers(), p.getVillagerLevel());
            case ClientboundOpenScreenPacket p -> {
                inv.open(p);
                bot.screens.book(false);
            }
            case ClientboundContainerClosePacket p -> {
                inv.closed();
                bot.screens.book(false);
            }
            case ClientboundRecipeBookAddPacket p -> bot.recipes.add(p);
            case ClientboundRecipeBookRemovePacket p -> bot.recipes.remove(p);
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean world(Packet<?> packet) {
        JavaWorld w = bot.world;
        switch (packet) {
            case ClientboundLevelChunkWithLightPacket p -> w.chunk(p);
            case ClientboundForgetLevelChunkPacket p -> w.forget(p.pos().x(), p.pos().z());
            case ClientboundBlockUpdatePacket p -> w.block(p.getPos().getX(), p.getPos().getY(), p.getPos().getZ(), JavaBlocks.id(p.getBlockState()));
            case ClientboundSectionBlocksUpdatePacket p -> p.runUpdates((pos, state) ->
                    w.block(pos.getX(), pos.getY(), pos.getZ(), JavaBlocks.id(state)));
            case ClientboundChunkBatchFinishedPacket p -> bot.send(new ServerboundChunkBatchReceivedPacket(CHUNKS_PER_TICK));
            case ClientboundAddEntityPacket p -> w.addEntity(p);
            case ClientboundRemoveEntitiesPacket p -> w.removeEntities(bot.ctx.compat().removedEntities(p));
            case ClientboundMoveEntityPacket p -> w.moveEntity(p);
            case ClientboundTeleportEntityPacket p -> w.teleportEntity(p);
            case ClientboundEntityPositionSyncPacket p -> w.syncEntity(p);
            case ClientboundPlayerInfoUpdatePacket p -> {
                if (p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                    for (ClientboundPlayerInfoUpdatePacket.Entry e : p.newEntries()) w.named(e.profileId(), e.profile().name());
                }
            }
            case ClientboundPlayerInfoRemovePacket p -> p.profileIds().forEach(w::unnamed);
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean talk(Packet<?> packet, long now) {
        switch (packet) {
            case ClientboundSystemChatPacket p -> bot.chat.message(p.overlay() ? "actionbar" : "system", p.content(), null, now);
            case ClientboundPlayerChatPacket p -> bot.chat.message("chat", bot.ctx.compat().chatContent(p), p.chatType().name().getString(), now);
            case ClientboundDisguisedChatPacket p -> bot.chat.message("chat", p.message(), p.chatType().name().getString(), now);
            case ClientboundResourcePackPushPacket p -> bot.packs.offered(p, now);
            case ClientboundResourcePackPopPacket p -> bot.packs.log().popped(p.id().orElse(null));
            default -> {
                return false;
            }
        }
        return true;
    }

    private void screens(Packet<?> packet) {
        switch (packet) {
            case ClientboundOpenSignEditorPacket p -> bot.screens.sign(bot.ctx.compat().signEditor(p));
            case ClientboundShowDialogPacket p -> bot.screens.dialog(p.dialog());
            case ClientboundClearDialogPacket p -> bot.screens.dialog(null);
            case ClientboundOpenBookPacket p -> bot.screens.book(true);
            default -> { }
        }
    }
}
