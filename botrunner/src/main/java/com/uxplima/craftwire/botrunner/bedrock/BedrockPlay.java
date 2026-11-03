package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.packet.Clientbound;
import com.uxplima.craftwire.bedrock.packet.EntityPackets;
import com.uxplima.craftwire.bedrock.packet.ItemPackets;
import com.uxplima.craftwire.bedrock.packet.LoginPackets;
import com.uxplima.craftwire.bedrock.packet.PlayerListPacket;
import com.uxplima.craftwire.bedrock.packet.StartGame;
import com.uxplima.craftwire.bedrock.packet.UiPackets;
import com.uxplima.craftwire.bedrock.packet.WorldPackets;

/**
 * Applies the server's packets to one Bedrock bot, as the client applies them: joining (status, packs, StartGame),
 * world and entities, the bot's own body and life, HUD, chat, forms and inventory. Runner loop thread.
 */
final class BedrockPlay {
    private final BedrockBot b;

    BedrockPlay(BedrockBot b) {
        this.b = b;
    }

    void apply(Clientbound p, long now) {
        switch (p) {
            case LoginPackets.PlayStatus s -> BedrockSpawn.status(b, s.status());
            case LoginPackets.Disconnect d -> b.kicked(d.message().isEmpty() ? "disconnected (reason " + d.reason() + ")" : d.message());
            case LoginPackets.ResourcePacksInfo i -> b.packs.info(i, now);
            case LoginPackets.ResourcePackStack s -> b.packs.stack();
            case LoginPackets.ResourcePackDataInfo d -> b.packs.dataInfo(d, now);
            case LoginPackets.ResourcePackChunkData c -> b.packs.chunk(c, now);
            case LoginPackets.Transfer t -> b.kicked("transferred to " + t.address() + ":" + t.port());
            case StartGame s -> BedrockSpawn.startGame(b, s);
            case ItemPackets.ItemRegistry r -> b.items.registry(r);
            case WorldPackets.LevelChunk c -> b.world.chunk(c);
            case WorldPackets.SubChunk s -> b.world.subChunks(s);
            case WorldPackets.UpdateBlock u -> b.world.block(u);
            case WorldPackets.BlockActorData d -> b.world.names().blockEntity(d.x(), d.y(), d.z(), d.nbt());
            case WorldPackets.ChunkPublisher c -> b.world.publisher(c);
            case PlayerListPacket l -> b.hud.players(l);
            case ItemPackets.InventoryContent c -> b.inventory.content(c);
            case ItemPackets.InventorySlot s -> b.inventory.slot(s);
            case ItemPackets.ContainerOpen o -> BedrockSpawn.containerOpened(b, o);
            case ItemPackets.ContainerClose c -> b.inventory.closed();
            case ItemPackets.StackResponse r -> stackResponse(r);
            case ItemPackets.Equipment e -> {
                if (e.runtimeId() == b.life.runtimeId()) b.life.heldSlot(e.hotbarSlot());
            }
            default -> {
                if (!entity(p)) ui(p, now);
            }
        }
    }

    /** Entity packets, including the ones about the bot itself; false when `p` is none of them. */
    private boolean entity(Clientbound p) {
        long self = b.life.runtimeId();
        switch (p) {
            case EntityPackets.AddEntity a -> b.entities.add(a, self);
            case EntityPackets.RemoveEntity r -> b.entities.remove(r.uniqueId());
            case EntityPackets.MoveEntity m -> b.entities.move(m);
            case EntityPackets.MoveSelf m -> {
                boolean placed = m.correction() || m.mode() == 1 || m.mode() == 2;
                if (m.correction() || m.runtimeId() == self) {
                    if (placed) b.moves.teleported(m.x(), m.y() - BedrockEntities.EYE, m.z(), m.yaw(), m.pitch());
                } else {
                    b.entities.movePlayer(m);
                }
            }
            case EntityPackets.Motion m -> {
                if (m.runtimeId() == self) b.body.motion(m.x(), m.y(), m.z());
            }
            case EntityPackets.Attributes a -> {
                if (a.runtimeId() == self || a.runtimeId() == -1) {
                    b.life.health(a.health());
                    b.life.food(a.hunger());
                }
            }
            case EntityPackets.GameMode g -> b.life.gameMode(g.mode());
            case EntityPackets.Respawn r -> BedrockSpawn.respawn(b, r);
            case EntityPackets.ChangeDimension c -> BedrockSpawn.changeDimension(b, c);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void ui(Clientbound p, long now) {
        switch (p) {
            case UiPackets.Text t -> text(t, now);
            case UiPackets.CommandOutput o -> {
                for (String m : o.messages()) b.inbox().add("system", BedrockHud.plain(m), null, now);
            }
            case UiPackets.Title t -> b.hud.title(t, now);
            case UiPackets.BossEvent e -> b.hud.boss(e);
            case UiPackets.DisplayObjective d -> b.hud.display(d);
            case UiPackets.RemoveObjective r -> b.hud.removeObjective(r.objective());
            case UiPackets.SetScore s -> b.hud.scores(s);
            case UiPackets.Form f -> b.forms.form(f);
            case UiPackets.CloseForms c -> b.forms.closeAll();
            case UiPackets.NpcDialogue d -> b.forms.npc(d);
            default -> { }
        }
    }

    /** Text: chat, whispers and announcements have a sender; tips and popups are the action bar; the rest are system lines. */
    private void text(UiPackets.Text t, long now) {
        String msg = BedrockHud.plain(t.parameters().isEmpty() ? t.message() : t.message() + " " + String.join(" ", t.parameters()));
        switch (t.type()) {
            case 1, 7, 8 -> b.inbox().add("chat", msg, t.source() == null ? null : BedrockHud.plain(t.source()), now);
            case 3, 4, 5 -> {
                b.hud.tip(msg, now);
                b.inbox().add("actionbar", msg, null, now);
            }
            default -> b.inbox().add("system", msg, null, now);
        }
    }

    private void stackResponse(ItemPackets.StackResponse r) {
        for (ItemPackets.StackResult res : r.results()) {
            b.clicks.answered(res.requestId(), res.ok());
            if (res.ok()) b.inventory.confirmed(res.slots());
        }
    }
}
