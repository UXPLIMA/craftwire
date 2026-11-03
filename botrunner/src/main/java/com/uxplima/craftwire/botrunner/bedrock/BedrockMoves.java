package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.packet.AuthInput;
import com.uxplima.craftwire.bedrock.packet.ItemUse;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.move.CellPhysics;
import com.uxplima.craftwire.bot.move.MoveInput;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * The bot's movement as a Bedrock client reports it: one PlayerAuthInput every tick (movement is server
 * authoritative, so the server moves the player from these and corrects it with CorrectPlayerMovePrediction), with
 * the keys held as input flags, the start/stop edges of sprinting, sneaking and jumping, and block actions or an
 * item interaction riding along. Inventory moves go as their own ItemStackRequest packets, as the client's UI sends
 * them. Positions are at eye height, as Bedrock sends player positions. Runner loop thread.
 */
final class BedrockMoves {
    private final CellPhysics body;
    private final List<int[]> blockActions = new ArrayList<>();
    private ItemUse itemUse;
    private long tick;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean wasSprinting;
    private boolean wasSneaking;
    private boolean wasOnGround = true;
    private boolean teleported;

    BedrockMoves(CellPhysics body) {
        this.body = body;
    }

    /** A server teleport or correction: the body goes there and the next input acknowledges it. */
    void teleported(double x, double feetY, double z, float yaw, float pitch) {
        body.teleport(x, feetY, z, yaw, pitch);
        lastX = x;
        lastY = feetY;
        lastZ = z;
        teleported = true;
    }

    void blockAction(int action, int x, int y, int z, int face) {
        blockActions.add(new int[] {action, x, y, z, face});
    }

    void itemUse(ItemUse use) {
        itemUse = use;
    }

    long tick() {
        return tick;
    }

    /** The input for this tick, after the body stepped. */
    AuthInput input() {
        Position p = body.position();
        MoveInput in = body.lastInput();
        BitSet flags = new BitSet();
        float forward = in.forward();
        if (forward > 0) flags.set(AuthInput.FLAG_UP);
        if (in.jump()) {
            flags.set(AuthInput.FLAG_JUMPING);
            flags.set(AuthInput.FLAG_WANT_UP);
        }
        if (wasOnGround && !body.onGround() && p.y() > lastY) {
            flags.set(AuthInput.FLAG_START_JUMPING);
            flags.set(AuthInput.FLAG_JUMP_DOWN);
            flags.set(AuthInput.FLAG_JUMPING);
        }
        edges(flags);
        if (body.horizontalCollision()) flags.set(AuthInput.FLAG_HORIZONTAL_COLLISION);
        if (teleported) flags.set(AuthInput.FLAG_HANDLED_TELEPORT);
        if (itemUse != null) flags.set(AuthInput.FLAG_ITEM_INTERACTION);
        if (!blockActions.isEmpty()) flags.set(AuthInput.FLAG_BLOCK_ACTIONS);
        float yaw = body.yaw();
        float pitch = body.pitch();
        AuthInput out = new AuthInput(pitch, yaw, (float) p.x(), (float) (p.y() + BedrockEntities.EYE), (float) p.z(), 0f, forward, flags,
                tick, (float) (p.x() - lastX), (float) (p.y() - lastY), (float) (p.z() - lastZ), itemUse, null,
                blockActions.isEmpty() ? null : List.copyOf(blockActions));
        lastX = p.x();
        lastY = p.y();
        lastZ = p.z();
        wasOnGround = body.onGround();
        teleported = false;
        itemUse = null;
        blockActions.clear();
        tick++;
        return out;
    }

    /** Sprint and sneak are held flags plus a start or stop flag on the tick they change. */
    private void edges(BitSet flags) {
        boolean sprint = body.sprintingNow();
        if (sprint) {
            flags.set(AuthInput.FLAG_SPRINTING);
            flags.set(AuthInput.FLAG_SPRINT_DOWN);
        }
        if (sprint && !wasSprinting) flags.set(AuthInput.FLAG_START_SPRINTING);
        if (!sprint && wasSprinting) flags.set(AuthInput.FLAG_STOP_SPRINTING);
        wasSprinting = sprint;
        boolean sneak = body.sneaking();
        if (sneak) {
            flags.set(AuthInput.FLAG_SNEAKING);
            flags.set(AuthInput.FLAG_SNEAK_DOWN);
        }
        if (sneak && !wasSneaking) flags.set(AuthInput.FLAG_START_SNEAKING);
        if (!sneak && wasSneaking) flags.set(AuthInput.FLAG_STOP_SNEAKING);
        wasSneaking = sneak;
    }
}
