package com.uxplima.craftwire.fabric.gui;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.ScreenAction;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.mixin.AbstractSignEditScreenAccessor;
import com.uxplima.craftwire.fabric.mixin.BookEditScreenAccessor;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

/**
 * The special screens for the client, in the same action names as the bots: each goes through the open screen or the
 * call the screen itself makes, so the client's view stays in step with the server. Dialogs and chat clicks live in
 * {@link ClientDialogs}. Render thread.
 */
public final class ClientScreenActions {
    /** SWAP and the edit-book packet name the off hand as inventory slot 40 (Inventory.SLOT_OFFHAND). */
    private static final int OFFHAND_SLOT = 40;

    private ClientScreenActions() {}

    public static JsonObject run(ScreenAction a) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || mc.getConnection() == null) throw notInWorld();
        Screen screen = mc.gui.screen();
        JsonObject r = new JsonObject();
        switch (a) {
            case ScreenAction.Rename n -> rename(screen, n, r);
            case ScreenAction.Sign s -> sign(screen, s, r);
            case ScreenAction.Book k -> book(mc, screen, k, r);
            case ScreenAction.Trade t -> {
                MerchantMenu m = menu(screen, MerchantMenu.class, "a merchant");
                // What MerchantScreen does on a trade button: hint, move the cost items, then tell the server.
                m.setSelectionHint(t.index());
                m.tryMoveItems(t.index());
                mc.getConnection().send(new ServerboundSelectTradePacket(t.index()));
                r.addProperty("selected", t.index());
            }
            case ScreenAction.Button b -> {
                AbstractContainerScreen<?> acs = container(screen);
                mc.gameMode.handleInventoryButtonClick(acs.getMenu().containerId, b.id());
                r.addProperty("button", b.id());
            }
            case ScreenAction.Beacon be -> beacon(mc, screen, be, r);
            case ScreenAction.Recipe rc -> recipe(mc, screen, rc, r);
            case ScreenAction.Creative c -> creative(mc, c, r);
            case ScreenAction.Dialog d -> ClientDialogs.answer(screen, d, r);
            case ScreenAction.ChatClick c -> ClientDialogs.chatClick(mc, c, r);
        }
        return r;
    }

    /** Types into the anvil's name box: its responder renames the item in the menu and sends the packet. */
    private static void rename(Screen screen, ScreenAction.Rename n, JsonObject r) {
        if (!(screen instanceof AnvilScreen anvil)) throw wrongScreen("an anvil");
        EditBox box = null;
        for (GuiEventListener child : anvil.children()) {
            if (child instanceof EditBox e) {
                box = e;
                break;
            }
        }
        if (box == null) throw wrongScreen("an anvil with a name field");
        box.setValue(n.text());
        r.addProperty("renamed", n.text());
    }

    /** Fills the open sign editor's lines; closing it sends them, as the Done button does. */
    private static void sign(Screen screen, ScreenAction.Sign s, JsonObject r) {
        if (!(screen instanceof AbstractSignEditScreen editor)) {
            throw new AgentError("NO_SIGN_EDITOR", "No sign editor is open",
                    "Right-click the sign first (input {action:'use'} while looking at it), then sign_edit.");
        }
        AbstractSignEditScreenAccessor acc = (AbstractSignEditScreenAccessor) editor;
        if (s.front() != acc.craftwire$isFrontText()) {
            throw new AgentError("WRONG_SIGN_SIDE", "The open editor is for the " + (acc.craftwire$isFrontText() ? "front" : "back") + " side",
                    "Pass side to match, or right-click the other side of the sign.");
        }
        String[] lines = acc.craftwire$getMessages();
        for (int i = 0; i < lines.length && i < s.lines().size(); i++) lines[i] = s.lines().get(i);
        editor.onClose();
        r.addProperty("side", s.front() ? "front" : "back");
        r.addProperty("lines", s.lines().size());
    }

    /** Sends ServerboundEditBookPacket as BookEditScreen's Done or Sign buttons do, for the book being edited or held. */
    private static void book(Minecraft mc, Screen screen, ScreenAction.Book k, JsonObject r) {
        LocalPlayer p = mc.player;
        int slot = k.slot();
        if (slot < 0) {
            InteractionHand hand = screen instanceof BookEditScreen edit ? ((BookEditScreenAccessor) edit).craftwire$getHand() : heldBook(p);
            slot = hand == InteractionHand.MAIN_HAND ? p.getInventory().getSelectedSlot() : OFFHAND_SLOT;
        }
        mc.getConnection().send(new ServerboundEditBookPacket(slot, k.pages(), Optional.ofNullable(k.title())));
        if (screen instanceof BookEditScreen) mc.gui.setScreen(null);
        r.addProperty("pages", k.pages().size());
        r.addProperty("signed", k.title() != null);
    }

    private static InteractionHand heldBook(LocalPlayer p) {
        if (p.getMainHandItem().is(Items.WRITABLE_BOOK)) return InteractionHand.MAIN_HAND;
        if (p.getOffhandItem().is(Items.WRITABLE_BOOK)) return InteractionHand.OFF_HAND;
        throw new AgentError("NO_BOOK", "No book and quill is open or held", "Hold a writable book (or pass slot), or open it with use.");
    }

    private static void beacon(Minecraft mc, Screen screen, ScreenAction.Beacon a, JsonObject r) {
        menu(screen, BeaconMenu.class, "a beacon");
        Optional<Holder<MobEffect>> primary = Optional.of(effect(a.primary()));
        Optional<Holder<MobEffect>> secondary = a.secondary() == null ? Optional.empty() : Optional.of(effect(a.secondary()));
        mc.getConnection().send(new ServerboundSetBeaconPacket(primary, secondary));
        r.addProperty("primary", a.primary());
        if (a.secondary() != null) r.addProperty("secondary", a.secondary());
    }

    private static Holder<MobEffect> effect(String id) {
        Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (key == null) throw BotErrors.invalid("Not an effect id: " + id);
        return BuiltInRegistries.MOB_EFFECT.get(key).<Holder<MobEffect>>map(h -> h)
                .orElseThrow(() -> BotErrors.invalid("Unknown effect " + id + "; e.g. speed, haste, strength, regeneration"));
    }

    /** The client only knows the display ids the server sent with the recipe book, not recipe ids. */
    private static void recipe(Minecraft mc, Screen screen, ScreenAction.Recipe a, JsonObject r) {
        if (a.displayId() == null) {
            throw new AgentError("UNSUPPORTED_ON_CLIENT", "The client knows recipe-book entries by display id, not by recipe id",
                    "Click the recipe in the recipe book (click_widget), or pass recipe (a display id).");
        }
        AbstractContainerScreen<?> acs = container(screen);
        mc.gameMode.handlePlaceRecipe(acs.getMenu().containerId, new RecipeDisplayId(a.displayId()), a.all());
        r.addProperty("recipe", a.displayId());
        r.addProperty("all", a.all());
    }

    /** Sets an inventory slot the way the creative inventory does (the server accepts it only in creative mode). */
    private static void creative(Minecraft mc, ScreenAction.Creative a, JsonObject r) {
        if (!mc.player.hasInfiniteMaterials()) {
            throw new AgentError("NOT_CREATIVE", "The player is not in creative mode", "server_command gamemode creative <player> first.");
        }
        Identifier key = Identifier.tryParse(a.item().contains(":") ? a.item() : "minecraft:" + a.item());
        Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        if (item == null || item == Items.AIR) throw BotErrors.invalid("Unknown item: " + a.item());
        mc.gameMode.handleCreativeModeItemAdd(new ItemStack(item, a.count()), a.slot());
        r.addProperty("slot", a.slot());
        r.addProperty("item", key.toString());
        r.addProperty("count", a.count());
    }

    static AbstractContainerScreen<?> container(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?> acs) return acs;
        throw new AgentError("NOT_A_CONTAINER", "The open screen has no menu", "Open the block or the plugin's menu first.");
    }

    private static <M> M menu(Screen screen, Class<M> type, String what) {
        if (screen instanceof AbstractContainerScreen<?> acs && type.isInstance(acs.getMenu())) return type.cast(acs.getMenu());
        throw wrongScreen(what);
    }

    private static AgentError wrongScreen(String what) {
        return new AgentError("WRONG_SCREEN", "The open screen is not " + what, "Open it first (use the block, or the plugin's command).");
    }

    private static AgentError notInWorld() {
        return new AgentError("NOT_IN_WORLD", "The player is not in a world.", "Join a world or server first.");
    }
}
