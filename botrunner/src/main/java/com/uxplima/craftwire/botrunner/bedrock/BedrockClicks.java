package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.packet.BedrockItem;
import com.uxplima.craftwire.bedrock.packet.StackRequest;
import com.uxplima.craftwire.bot.gui.RawClick;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java-style container clicks as Bedrock ItemStackRequest actions, the way the Bedrock client turns a tap into
 * take, place, swap or drop. Each click is predicted locally (the client shows the result at once) and the
 * snapshot kept until the server answers: a rejected request restores it. Runner loop thread.
 */
final class BedrockClicks {
    private record Snapshot(StackRequest.Slot ref, BedrockItem before) {}

    private final BedrockInventory inv;
    private final Map<Integer, List<Snapshot>> pending = new HashMap<>();
    private int nextId = -1;

    BedrockClicks(BedrockInventory inv) {
        this.inv = inv;
    }

    /** The request for a click, already applied to the local view; null when the click changes nothing. */
    StackRequest plan(RawClick c) {
        if (c.slot() == RawClick.OUTSIDE) return dropCursor(c.button() == 0);
        StackRequest.Slot slot = BedrockSlots.ofRaw(inv, c.slot());
        return switch (c.mode()) {
            case PICKUP -> pickup(slot, c.button() == 1);
            case QUICK_MOVE -> quickMove(c.slot(), slot);
            case SWAP -> swap(slot, c.button());
            case THROW -> drop(slot, c.button() == 1);
            default -> throw new AgentError("UNSUPPORTED_FOR_KIND", "Bedrock bots have no " + c.mode() + " click",
                    "Use pickup, quick_move, swap or throw clicks.");
        };
    }

    private StackRequest pickup(StackRequest.Slot slot, boolean right) {
        BedrockItem at = BedrockSlots.get(inv, slot);
        BedrockItem held = inv.cursor;
        StackRequest.Slot cursor = BedrockSlots.cursor(inv);
        if (held.empty()) {
            if (at.empty()) return null;
            int n = right ? (at.count() + 1) / 2 : at.count();
            return request(List.of(new StackRequest.Take(n, slot, cursor)), slot, cursor, () -> move(slot, cursor, n));
        }
        if (at.empty() || stacks(at, held)) {
            int room = BedrockItems.maxStack(nameOf(held)) - (at.empty() ? 0 : at.count());
            int n = Math.min(right ? 1 : held.count(), room);
            if (n <= 0) return null;
            return request(List.of(new StackRequest.Place(n, cursor, slot)), slot, cursor, () -> move(cursor, slot, n));
        }
        return request(List.of(new StackRequest.Swap(cursor, slot)), slot, cursor, () -> exchange(cursor, slot));
    }

    /** Shift-click: the whole stack to the first fitting slot of the other side (menu to inventory or back). */
    private StackRequest quickMove(int raw, StackRequest.Slot from) {
        BedrockItem it = BedrockSlots.get(inv, from);
        if (it.empty()) return null;
        StackRequest.Slot to = target(raw, it);
        if (to == null) return null;
        int n = it.count();
        return request(List.of(new StackRequest.Place(n, from, to)), from, to, () -> move(from, to, n));
    }

    private StackRequest.Slot target(int raw, BedrockItem it) {
        boolean fromMenu = inv.menuOpen() && raw < inv.menuSize();
        List<StackRequest.Slot> candidates = new ArrayList<>();
        if (fromMenu) {
            for (int i = 8; i >= 0; i--) candidates.add(BedrockSlots.inventory(inv, i));
            for (int i = 35; i >= 9; i--) candidates.add(BedrockSlots.inventory(inv, i));
        } else if (inv.menuOpen()) {
            for (int i = 0; i < inv.menuSize(); i++) candidates.add(BedrockSlots.ofRaw(inv, i));
        } else {
            boolean hotbar = raw >= 36;
            for (int i = hotbar ? 9 : 0; i < (hotbar ? 36 : 9); i++) candidates.add(BedrockSlots.inventory(inv, i));
        }
        for (StackRequest.Slot s : candidates) {
            BedrockItem at = BedrockSlots.get(inv, s);
            if (!at.empty() && stacks(at, it) && at.count() + it.count() <= BedrockItems.maxStack(nameOf(it))) return s;
        }
        for (StackRequest.Slot s : candidates) if (BedrockSlots.get(inv, s).empty()) return s;
        return null;
    }

    /** Number keys 0-8 swap with that hotbar slot; 40 with the off hand. */
    private StackRequest swap(StackRequest.Slot slot, int button) {
        if (button != 40 && (button < 0 || button > 8)) {
            throw new AgentError("INVALID_PARAMS", "swap button must be 0-8 or 40", "40 is the off hand.");
        }
        StackRequest.Slot other = button == 40 ? BedrockSlots.offhand(inv) : BedrockSlots.inventory(inv, button);
        if (BedrockSlots.get(inv, slot).empty() && BedrockSlots.get(inv, other).empty()) return null;
        return request(List.of(new StackRequest.Swap(slot, other)), slot, other, () -> exchange(slot, other));
    }

    private StackRequest drop(StackRequest.Slot slot, boolean all) {
        BedrockItem at = BedrockSlots.get(inv, slot);
        if (at.empty()) return null;
        int n = all ? at.count() : 1;
        return request(List.of(new StackRequest.Drop(n, slot)), slot, slot, () -> take(slot, n));
    }

    private StackRequest dropCursor(boolean all) {
        BedrockItem held = inv.cursor;
        if (held.empty()) return null;
        StackRequest.Slot cursor = BedrockSlots.cursor(inv);
        int n = all ? held.count() : 1;
        return request(List.of(new StackRequest.Drop(n, cursor)), cursor, cursor, () -> take(cursor, n));
    }

    /** A request the server's answer alone updates (drop, swap hands): the touched slots are only restored on rejection. */
    StackRequest unpredicted(List<StackRequest.Action> actions, StackRequest.Slot a, StackRequest.Slot b) {
        return request(actions, a, b, () -> { });
    }

    private StackRequest request(List<StackRequest.Action> actions, StackRequest.Slot a, StackRequest.Slot b, Runnable predict) {
        int id = nextId;
        // Requests are numbered like the client's legacy ids: negative and odd, counting down.
        nextId -= 2;
        pending.put(id, List.of(new Snapshot(a, BedrockSlots.get(inv, a)), new Snapshot(b, BedrockSlots.get(inv, b))));
        predict.run();
        return new StackRequest(id, actions);
    }

    /** The server's verdict: accepted requests keep the prediction (and take its stack ids), rejected ones roll back. */
    void answered(int requestId, boolean ok) {
        List<Snapshot> s = pending.remove(requestId);
        if (s == null || ok) return;
        for (int i = s.size() - 1; i >= 0; i--) BedrockSlots.set(inv, s.get(i).ref(), s.get(i).before());
    }

    private void move(StackRequest.Slot from, StackRequest.Slot to, int n) {
        BedrockItem src = BedrockSlots.get(inv, from);
        BedrockItem dst = BedrockSlots.get(inv, to);
        int have = dst.empty() ? 0 : dst.count();
        BedrockSlots.set(inv, to, withCount(src, have + n));
        BedrockSlots.set(inv, from, src.count() - n <= 0 ? BedrockItem.EMPTY : withCount(src, src.count() - n));
    }

    private void exchange(StackRequest.Slot a, StackRequest.Slot b) {
        BedrockItem x = BedrockSlots.get(inv, a);
        BedrockSlots.set(inv, a, BedrockSlots.get(inv, b));
        BedrockSlots.set(inv, b, x);
    }

    private void take(StackRequest.Slot from, int n) {
        BedrockItem src = BedrockSlots.get(inv, from);
        BedrockSlots.set(inv, from, src.count() - n <= 0 ? BedrockItem.EMPTY : withCount(src, src.count() - n));
    }

    private static BedrockItem withCount(BedrockItem it, int n) {
        return new BedrockItem(it.networkId(), n, it.meta(), it.stackId(), it.blockRuntimeId(), it.nbt(), it.userData());
    }

    private static boolean stacks(BedrockItem a, BedrockItem b) {
        return a.networkId() == b.networkId() && a.meta() == b.meta() && a.nbt().equals(b.nbt());
    }

    private String nameOf(BedrockItem it) {
        return inv.items().name(it.networkId());
    }
}
