package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.bot.model.BlockPoint;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.move.BotBody;
import com.uxplima.craftwire.bot.move.MoveInput;
import com.uxplima.craftwire.bot.move.Steering;
import com.uxplima.craftwire.bot.path.BlockView;
import com.uxplima.craftwire.paper.bot.path.PaperBlockView;
import com.uxplima.craftwire.paper.compat.ServerCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The plugin bot's body for the bot-core walker: the server player's own position and movement fields. A real
 * client's movement packets drive the player; here the walker sets the input fields and the bot's tick runs the
 * player's physics. Owning thread of the player only.
 */
final class PaperBody implements BotBody {
    private final Bot bot;
    private PaperBlockView view;
    private ServerLevel viewLevel;

    PaperBody(Bot bot) {
        this.bot = bot;
    }

    private ServerPlayer p() {
        return bot.player();
    }

    @Override
    public Position position() {
        Vec3 v = p().position();
        return new Position(v.x, v.y, v.z);
    }

    @Override
    public float yaw() {
        return p().getYRot();
    }

    @Override
    public String world() {
        return p().level().dimension().identifier().toString();
    }

    @Override
    public boolean onGround() {
        return p().onGround();
    }

    @Override
    public boolean inWater() {
        return p().isInWater();
    }

    @Override
    public boolean onClimbable() {
        return p().onClimbable();
    }

    @Override
    public boolean horizontalCollision() {
        return p().horizontalCollision;
    }

    @Override
    public boolean sneaking() {
        return p().isShiftKeyDown();
    }

    @Override
    public BlockView blocks() {
        ServerLevel level = (ServerLevel) p().level();
        if (view == null || viewLevel != level) {
            view = new PaperBlockView(level);
            viewLevel = level;
        }
        return view;
    }

    @Override
    public void input(MoveInput in) {
        ServerPlayer p = p();
        p.setYRot(in.yaw());
        p.setYHeadRot(in.yaw());
        p.zza = in.forward();
        p.setSprinting(in.sprint());
        p.setJumping(in.jump());
    }

    @Override
    public void sprinting(boolean on) {
        bot.sprint(on);
    }

    /** Right-clicks the block like a player: PlayerInteractEvent fires, so a protection plugin can refuse. */
    @Override
    public void useBlock(BlockPoint b) {
        ServerPlayer p = p();
        BlockPos pos = new BlockPos(b.x(), b.y(), b.z());
        Vec3 hit = Vec3.atCenterOf(pos);
        Vec3 eye = p.getEyePosition();
        Direction face = Direction.getApproximateNearest(eye.x - hit.x, eye.y - hit.y, eye.z - hit.z);
        bot.look(Steering.yaw(hit.x - eye.x, hit.z - eye.z), Steering.pitch(hit.x - eye.x, hit.y - eye.y, hit.z - eye.z));
        bot.listener().handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, pos, false), bot.nextSequence()));
        ServerCompat.get().swingAfterInteraction(bot.listener(), InteractionHand.MAIN_HAND);
    }
}
