package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.ItemJson;
import com.uxplima.craftwire.fabric.mixin.AbstractContainerScreenAccessor;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class GuiReadHandler {
    private GuiReadHandler() {}

    public static JsonObject read() {
        Screen s = Minecraft.getInstance().gui.screen();
        JsonObject o = new JsonObject();
        o.addProperty("open", s != null);
        if (s == null) return o;
        o.addProperty("title", s.getTitle().getString());
        o.addProperty("type", s.getClass().getSimpleName());

        if (s instanceof AbstractContainerScreen<?> acs) {
            List<Slot> slots = acs.getMenu().slots;
            o.addProperty("slotCount", slots.size());
            JsonArray arr = new JsonArray();
            for (int i = 0; i < slots.size(); i++) {
                ItemStack stack = slots.get(i).getItem();
                if (stack.isEmpty()) continue;
                JsonObject item = ItemJson.of(stack, true);
                item.addProperty("slot", i);
                item.addProperty("container", slots.get(i).container instanceof Inventory ? "player" : "menu");
                arr.add(item);
            }
            o.add("slots", arr);
            Slot hovered = ((AbstractContainerScreenAccessor) acs).craftwire$getHoveredSlot();
            if (hovered == null) {
                o.add("hovered", JsonNull.INSTANCE);
            } else {
                JsonObject h = new JsonObject();
                h.addProperty("slot", slots.indexOf(hovered));
                if (hovered.hasItem()) h.add("item", ItemJson.of(hovered.getItem(), true));
                o.add("hovered", h);
            }
        }

        JsonArray widgets = new JsonArray();
        int index = 0;
        for (GuiEventListener child : s.children()) {
            if (!(child instanceof AbstractWidget w)) continue;
            JsonObject wj = new JsonObject();
            wj.addProperty("index", index++);
            wj.addProperty("kind", w.getClass().getSimpleName());
            wj.addProperty("text", w.getMessage().getString());
            wj.addProperty("x", w.getX());
            wj.addProperty("y", w.getY());
            wj.addProperty("width", w.getWidth());
            wj.addProperty("height", w.getHeight());
            if (w instanceof EditBox box) wj.addProperty("value", box.getValue());
            widgets.add(wj);
        }
        o.add("widgets", widgets);
        return o;
    }
}
