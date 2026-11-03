package com.uxplima.craftwire.botrunner.javabot.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.model.BlockPoint;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.move.Steering;
import com.uxplima.craftwire.botrunner.javabot.JavaCompat;
import com.uxplima.craftwire.mc.DialogModel;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Screens the server opened for the bot that are not container menus (the sign editor, a dialog, a book), how
 * they show in gui_read, and the client-side clicks around them: running a click event as the vanilla client does
 * and right-clicking a block. Runner loop thread.
 */
final class JavaScreens {
    /** Eye height of a standing player. */
    private static final double EYE = 1.62;

    private final JavaBot bot;
    private JavaCompat.SignEditor sign;
    private Holder<Dialog> dialog;
    private boolean book;

    JavaScreens(JavaBot bot) {
        this.bot = bot;
    }

    void reset() {
        sign = null;
        dialog = null;
        book = false;
    }

    JavaCompat.SignEditor sign() {
        return sign;
    }

    void sign(JavaCompat.SignEditor s) {
        sign = s;
    }

    Holder<Dialog> dialog() {
        return dialog;
    }

    void dialog(Holder<Dialog> d) {
        dialog = d;
    }

    void book(boolean open) {
        book = open;
    }

    /** gui_read's `screen` when no menu is open: the plugin bot's shape (BotScreenInfo). Null when nothing is open. */
    JsonObject json() {
        JsonObject o = new JsonObject();
        if (sign != null) {
            JsonObject s = new JsonObject();
            s.addProperty("x", sign.pos().getX());
            s.addProperty("y", sign.pos().getY());
            s.addProperty("z", sign.pos().getZ());
            s.addProperty("side", sign.front() ? "front" : "back");
            o.add("sign", s);
        }
        if (dialog != null) o.add("dialog", DialogModel.json(dialog.value()));
        if (book) o.addProperty("book", true);
        return o.isEmpty() ? null : o;
    }

    /** Escape with no menu open: a dialog runs its cancel action and closes; a sign editor or book just closes. */
    void escape() {
        Holder<Dialog> d = dialog;
        reset();
        if (d == null) return;
        Dialog value = d.value();
        DialogModel.Button cancel = new DialogModel.Button("cancel", value.onCancel(), null);
        DialogModel.clickEvent(value, cancel, Map.of()).ifPresent(this::run);
    }

    /** Runs a click event like the vanilla client when a player clicks chat text or a dialog button. */
    JsonObject run(ClickEvent e) {
        JsonObject r = new JsonObject();
        switch (e) {
            case ClickEvent.RunCommand c -> {
                String command = c.command().startsWith("/") ? c.command().substring(1) : c.command();
                bot.command(command);
                r.addProperty("ran", "/" + command);
            }
            case ClickEvent.Custom c -> {
                bot.send(new ServerboundCustomClickActionPacket(c.id(), c.payload()));
                r.addProperty("custom", c.id().toString());
            }
            case ClickEvent.ShowDialog d -> {
                dialog = d.dialog();
                r.addProperty("opened", "dialog");
            }
            case ClickEvent.SuggestCommand s -> r.addProperty("suggested", s.command());
            case ClickEvent.OpenUrl u -> {
                r.addProperty("url", u.uri().toString());
                r.addProperty("followed", false);
            }
            case ClickEvent.CopyToClipboard c -> r.addProperty("copied", c.value());
            case ClickEvent.ChangePage p -> r.addProperty("page", p.page());
            default -> r.addProperty("ignored", e.action().getSerializedName());
        }
        return r;
    }

    /** Right-clicks a block's centre on the face towards the bot (doors and gates on a walk, signs to edit). */
    void useBlock(BlockPoint b) {
        Position eye = bot.position().add(0, EYE, 0);
        Vec3 center = Vec3.atCenterOf(new BlockPos(b.x(), b.y(), b.z()));
        Direction face = Direction.getApproximateNearest(eye.x() - center.x, eye.y() - center.y, eye.z() - center.z);
        useOn(new BlockPos(b.x(), b.y(), b.z()), face, InteractionHand.MAIN_HAND);
    }

    /** Looks at the face's centre and sends the use-item-on packet with the hand's swing. */
    void useOn(BlockPos pos, Direction face, InteractionHand hand) {
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        lookAt(hit.x, hit.y, hit.z);
        bot.send(new ServerboundUseItemOnPacket(hand, new BlockHitResult(hit, face, pos, false), bot.nextSequence()));
        bot.ctx.compat().swing(hand == InteractionHand.MAIN_HAND).forEach(bot::send);
    }

    void lookAt(double x, double y, double z) {
        Position eye = bot.position().add(0, EYE, 0);
        double dx = x - eye.x();
        double dy = y - eye.y();
        double dz = z - eye.z();
        bot.look(Steering.yaw(dx, dz), Steering.pitch(dx, dy, dz));
    }

    double eyeDistance(double x, double y, double z) {
        return bot.position().add(0, EYE, 0).distance(new Position(x, y, z));
    }
}
