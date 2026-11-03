package com.uxplima.craftwire.botrunner.javabot.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.PlayerSlots;
import com.uxplima.craftwire.bot.gui.SlotItem;
import com.uxplima.craftwire.bot.model.ItemInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * The bot's inventory and open menu as the server last described them (content, slot, cursor and data packets),
 * in raw menu slot order, plus the extras a special menu shows (merchant offers, container data such as the anvil
 * cost). Runner loop thread.
 */
final class JavaInventory {
    /** Player menu rows that every container menu appends after its own slots. */
    private static final int PLAYER_ROWS = 36;

    private final ItemStack[] player = new ItemStack[PlayerSlots.COUNT];
    private int playerState;
    private int containerId;
    private String type;
    private String title;
    private List<ItemStack> menu;
    private int menuState;
    private ItemStack carried = ItemStack.EMPTY;
    private final Map<Integer, Integer> data = new HashMap<>();
    private MerchantOffers offers;
    private int traderLevel;

    JavaInventory() {
        Arrays.fill(player, ItemStack.EMPTY);
    }

    void content(ClientboundContainerSetContentPacket p) {
        if (p.containerId() == 0) {
            for (int i = 0; i < Math.min(player.length, p.items().size()); i++) player[i] = p.items().get(i);
            playerState = p.stateId();
        } else if (p.containerId() == containerId) {
            menu = new ArrayList<>(p.items());
            menuState = p.stateId();
        }
        carried = p.carriedItem();
    }

    void slot(ClientboundContainerSetSlotPacket p) {
        if (p.getContainerId() == 0 && p.getSlot() >= 0 && p.getSlot() < player.length) {
            player[p.getSlot()] = p.getItem();
            playerState = p.getStateId();
        } else if (p.getContainerId() == containerId && menu != null && p.getSlot() >= 0 && p.getSlot() < menu.size()) {
            menu.set(p.getSlot(), p.getItem());
            menuState = p.getStateId();
        }
    }

    /** ClientboundSetPlayerInventoryPacket names inventory indices (0-8 hotbar, 9-35 main, 36-39 armour, 40 off hand). */
    void inventorySlot(int index, ItemStack item) {
        if (index >= 0 && index <= 40) player[raw(index)] = item;
    }

    /** An inventory index (0-40) as its raw slot of the player's menu. */
    private static int raw(int index) {
        if (index <= 8) return PlayerSlots.HOTBAR_START + index;
        if (index <= 35) return index;
        if (index <= 39) return PlayerSlots.FEET - (index - 36);
        return PlayerSlots.OFFHAND;
    }

    void cursor(ItemStack item) {
        carried = item;
    }

    void open(ClientboundOpenScreenPacket p) {
        containerId = p.getContainerId();
        type = BuiltInRegistries.MENU.getKey(p.getType()) == null ? "unknown" : BuiltInRegistries.MENU.getKey(p.getType()).toString();
        title = p.getTitle().getString();
        menu = null;
        data.clear();
        offers = null;
    }

    void closed() {
        containerId = 0;
        menu = null;
        data.clear();
        offers = null;
    }

    void data(int container, int id, int value) {
        if (container == containerId) data.put(id, value);
    }

    void offers(int container, MerchantOffers o, int level) {
        if (container != containerId) return;
        offers = o;
        traderLevel = level;
    }

    boolean menuOpen() {
        return containerId != 0;
    }

    int containerId() {
        return containerId;
    }

    /** The open menu's type id (minecraft:anvil), or null with none open. */
    String type() {
        return containerId != 0 ? type : null;
    }

    int stateId() {
        return containerId != 0 ? menuState : playerState;
    }

    ItemStack item(int raw) {
        return raw >= 0 && raw < player.length ? player[raw] : ItemStack.EMPTY;
    }

    /** The player's items by inventory index (0-8 hotbar, 9-35 main, 36-39 boots to helmet, 40 off hand), for state. */
    List<SlotItem> byInventoryIndex() {
        List<SlotItem> out = new ArrayList<>();
        for (int index = 0; index <= 40; index++) {
            ItemInfo info = JavaItems.info(player[raw(index)]);
            if (info != null) out.add(new SlotItem(index, false, info));
        }
        return out;
    }

    GuiView view() {
        boolean open = containerId != 0 && menu != null;
        List<ItemStack> slots = open ? menu : Arrays.asList(player);
        int top = open ? Math.max(0, slots.size() - PLAYER_ROWS) : 0;
        List<SlotItem> items = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            ItemInfo info = JavaItems.info(slots.get(i));
            if (info != null) items.add(new SlotItem(i, open ? i < top : i < PlayerSlots.MAIN_START, info));
        }
        return new GuiView(open, open ? title : "Inventory", open ? type : "INVENTORY", containerId, stateId(), slots.size(), top, items,
                JavaItems.info(carried), screen());
    }

    /**
     * What the open menu shows beyond its slots, in the plugin bot's shape (MenuInfo), from the menu's data slots as
     * the vanilla menus number them. Fields a client can only compute from recipes it was never sent are left out.
     */
    private JsonObject screen() {
        if (containerId == 0 || type == null) return null;
        JsonObject o = new JsonObject();
        switch (type) {
            case "minecraft:anvil" -> {
                o.addProperty("kind", "anvil");
                o.addProperty("cost", data.getOrDefault(0, 0));
            }
            case "minecraft:enchantment" -> {
                o.addProperty("kind", "enchanting");
                JsonArray costs = new JsonArray();
                for (int i = 0; i < 3; i++) costs.add(data.getOrDefault(i, 0));
                o.add("costs", costs);
                // The lapis slot is the menu's second slot.
                o.addProperty("lapis", menu != null && menu.size() > 1 ? menu.get(1).getCount() : 0);
            }
            case "minecraft:merchant" -> merchant(o);
            case "minecraft:stonecutter", "minecraft:loom" -> {
                o.addProperty("kind", type.substring("minecraft:".length()));
                o.addProperty("selected", data.getOrDefault(0, -1));
            }
            case "minecraft:beacon" -> {
                o.addProperty("kind", "beacon");
                o.addProperty("levels", data.getOrDefault(0, 0));
            }
            case "minecraft:lectern" -> {
                o.addProperty("kind", "lectern");
                o.addProperty("page", data.getOrDefault(0, 0));
            }
            default -> { }
        }
        return o.isEmpty() ? null : o;
    }

    private void merchant(JsonObject o) {
        o.addProperty("kind", "merchant");
        o.addProperty("level", traderLevel);
        if (offers == null) return;
        JsonArray list = new JsonArray();
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            JsonObject t = new JsonObject();
            t.addProperty("index", i);
            t.add("costA", JavaItems.json(offer.getCostA()));
            if (!offer.getCostB().isEmpty()) t.add("costB", JavaItems.json(offer.getCostB()));
            t.add("result", JavaItems.json(offer.getResult()));
            t.addProperty("uses", offer.getUses());
            t.addProperty("maxUses", offer.getMaxUses());
            t.addProperty("outOfStock", offer.isOutOfStock());
            list.add(t);
        }
        o.add("offers", list);
    }
}
