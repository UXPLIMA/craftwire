package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

final class GuiChecks {
    private GuiChecks() {}

    private static int slotOf(JsonObject gui, String itemId) {
        for (JsonElement e : gui.getAsJsonArray("slots")) {
            JsonObject s = e.getAsJsonObject();
            if (s.get("id").getAsString().equals(itemId)) return s.get("slot").getAsInt();
        }
        return -1;
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("clear @a");
        sp.getServer().runCommand("give @a minecraft:diamond 5");
        ctx.waitTicks(5);
        ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        ctx.waitTicks(2);

        JsonObject gui = Calls.call(ctx, "gui.read", "{}").getAsJsonObject();
        check(gui.get("open").getAsBoolean(), "inventory should be open");
        check(gui.get("type").getAsString().equals("InventoryScreen"), "type: " + gui.get("type"));
        int diamond = slotOf(gui, "minecraft:diamond");
        check(diamond >= 0, "diamond slot not found: " + gui);

        JsonObject hovered = Calls.call(ctx, "gui.action", "{\"action\":\"hover\",\"slot\":" + diamond + "}").getAsJsonObject();
        check(hovered.getAsJsonObject("hovered").get("slot").getAsInt() == diamond, "hovered slot: " + hovered.get("hovered"));
        check(hovered.getAsJsonObject("hovered").getAsJsonObject("item").get("id").getAsString().equals("minecraft:diamond"), "hovered item");

        int target = 9; // first main-inventory slot of InventoryMenu
        JsonObject moved = Calls.call(ctx, "gui.action", "{\"action\":\"drag\",\"slot\":" + diamond + ",\"toSlot\":" + target + "}").getAsJsonObject();
        check(slotOf(moved, "minecraft:diamond") == target, "diamond should move to slot 9: " + moved.get("slots"));

        check("SLOT_OUT_OF_RANGE".equals(Calls.error(ctx, "gui.action", "{\"action\":\"click\",\"slot\":999}").code()), "slot 999");

        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");
        ctx.waitTicks(2);
        check(!Calls.call(ctx, "gui.read", "{}").getAsJsonObject().get("open").getAsBoolean(), "closed");
        // Review Focus #5: acting on a stale slot after the screen closed is an error, not a crash.
        check("NO_SCREEN_OPEN".equals(Calls.error(ctx, "gui.action", "{\"action\":\"click\",\"slot\":" + target + "}").code()), "stale slot after close");

        ctx.setScreen(() -> new PauseScreen(true));
        ctx.waitTicks(2);
        JsonObject pause = Calls.call(ctx, "gui.read", "{}").getAsJsonObject();
        check(pause.getAsJsonArray("widgets").size() > 0, "pause screen widgets: " + pause);
        check("WIDGET_NOT_FOUND".equals(Calls.error(ctx, "gui.action", "{\"action\":\"click_widget\",\"widget\":\"No Such Button\"}").code()), "missing widget");
        JsonObject after = Calls.call(ctx, "gui.action", "{\"action\":\"click_widget\",\"widget\":\"Back to Game\"}").getAsJsonObject();
        check(!after.get("open").getAsBoolean(), "Back to Game should close the pause screen: " + after);
    }
}
