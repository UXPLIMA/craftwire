package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.mc.ChatClicks;
import com.uxplima.craftwire.paper.compat.ServerCompat;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * What a bot takes from the packets the server sends it, before they are dropped: messages with their clickable
 * parts, the HUD copy, teleports to confirm, resource pack offers, and the sign editor, dialog and book screens.
 * Any thread (plugins send from async tasks).
 */
final class BotPackets {
    private BotPackets() {}

    static void accept(BotLink link, String name, Packet<?> msg, long now) {
        if (msg instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) accept(link, name, sub, now);
            return;
        }
        boolean tab = msg instanceof ClientboundPlayerInfoUpdatePacket || msg instanceof ClientboundPlayerInfoRemovePacket;
        if (!tab || link.tabWatched()) {
            try {
                link.hud.accept(msg, now);
            } catch (RuntimeException e) {
                // A packet the HUD copy cannot apply (odd plugin ordering) must never break the bot.
            }
        }
        switch (msg) {
            case ClientboundPlayerPositionPacket p -> link.pendingTeleport.set(p.id());
            case ClientboundSystemChatPacket p -> message(link, p.overlay() ? "actionbar" : "system", p.content(), null, now);
            case ClientboundPlayerChatPacket p -> {
                message(link, "chat", ServerCompat.get().chatComponent(p), p.chatType().name().getString(), now);
            }
            case ClientboundDisguisedChatPacket p -> message(link, "chat", p.message(), p.chatType().name().getString(), now);
            case ClientboundResourcePackPushPacket p -> link.packAnswers().offered(link, p, now);
            case ClientboundResourcePackPopPacket p -> link.packs.popped(p.id().orElse(null));
            case ClientboundOpenSignEditorPacket p -> link.screens.sign(ServerCompat.get().signEditor(p));
            case ClientboundShowDialogPacket p -> link.screens.dialog(p.dialog());
            case ClientboundClearDialogPacket p -> link.screens.dialog(null);
            case ClientboundOpenBookPacket p -> link.screens.book(true);
            case ClientboundOpenScreenPacket p -> link.screens.book(false);
            case ClientboundContainerClosePacket p -> link.screens.book(false);
            default -> { }
        }
    }

    private static void message(BotLink link, String kind, Component c, String sender, long now) {
        List<ChatClicks.Part> parts = ChatClicks.parts(c);
        Message m = link.inbox.add(kind, c.getString(), sender, parts.stream().map(ChatClicks.Part::action).toList(), ChatClicks.hover(c), now);
        if (!parts.isEmpty()) link.rememberClicks(m.id(), parts.stream().map(ChatClicks.Part::event).toList());
    }
}
