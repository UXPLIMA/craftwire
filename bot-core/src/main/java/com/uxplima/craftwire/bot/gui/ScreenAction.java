package com.uxplima.craftwire.bot.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BlockPoint;
import com.uxplima.craftwire.bot.model.BotErrors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The special screens beyond slot clicks, each sent the way the vanilla client sends it: anvil rename
 * (ServerboundRenameItemPacket), sign edit (ServerboundSignUpdatePacket), book edit (ServerboundEditBookPacket),
 * merchant trade (ServerboundSelectTradePacket), menu buttons for enchanting, stonecutter, loom and lectern
 * (ServerboundContainerButtonClickPacket), beacon (ServerboundSetBeaconPacket), recipe book
 * (ServerboundPlaceRecipePacket), creative inventory (ServerboundSetCreativeModeSlotPacket), Paper dialogs
 * (ServerboundCustomClickActionPacket) and clickable chat.
 */
public sealed interface ScreenAction {
    /** The bot_action names this type parses. */
    List<String> ACTIONS = List.of("anvil_rename", "sign_edit", "book_edit", "trade_select", "menu_button", "beacon", "recipe_place",
            "creative_set", "dialog_answer", "chat_click");

    record Rename(String text) implements ScreenAction {}

    /** `block` null means the sign editor the server opened for the bot. */
    record Sign(BlockPoint block, boolean front, List<String> lines) implements ScreenAction {}

    /** `slot` is a hotbar slot 0-8, or 40 for the off hand; `title` null keeps the book unsigned. */
    record Book(int slot, List<String> pages, String title) implements ScreenAction {}

    record Trade(int index) implements ScreenAction {}

    record Button(int id) implements ScreenAction {}

    /** Effect ids such as minecraft:speed; `secondary` may be null. */
    record Beacon(String primary, String secondary) implements ScreenAction {}

    /** `displayId` from the recipe book the server sent, or `result` (an item id) to look one up. */
    record Recipe(Integer displayId, String result, boolean all) implements ScreenAction {}

    record Creative(int slot, String item, int count) implements ScreenAction {}

    /** `button` is a button label or its index; `inputs` are the dialog's input values by key. */
    record Dialog(String button, Map<String, String> inputs) implements ScreenAction {}

    /** The message by `messageId`, else the newest clickable one containing `match`; `part` picks its clickable part. */
    record ChatClick(Long messageId, String match, int part) implements ScreenAction {}

    static boolean handles(String action) {
        return ACTIONS.contains(action);
    }

    static ScreenAction parse(String action, JsonObject p) {
        return switch (action) {
            case "anvil_rename" -> new Rename(limited(Params.string(p, "text"), 50, "text"));
            case "sign_edit" -> sign(p);
            case "book_edit" -> book(p);
            case "trade_select" -> new Trade(Params.clampedInt(p, "index", 0, 0, 255));
            case "menu_button" -> new Button(Params.clampedInt(p, "id", 0, 0, 255));
            case "beacon" -> new Beacon(Params.string(p, "primary"), Params.optString(p, "secondary").orElse(null));
            case "recipe_place" -> new Recipe(Params.optInt(p, "recipe").orElse(null), Params.optString(p, "result").orElse(null),
                    Params.bool(p, "all", false));
            case "creative_set" -> new Creative(Params.integer(p, "slot"), Params.string(p, "item"), Params.clampedInt(p, "count", 1, 1, 99));
            case "dialog_answer" -> dialog(p);
            case "chat_click" -> chatClick(p);
            default -> throw BotErrors.invalid("Unknown screen action: " + action);
        };
    }

    private static Sign sign(JsonObject p) {
        List<String> lines = Params.strings(p, "lines");
        if (lines.size() > 4) throw BotErrors.invalid("a sign has 4 lines");
        while (lines.size() < 4) lines.add("");
        for (String l : lines) limited(l, 384, "a sign line");
        BlockPoint block = Params.optObject(p, "block").map(BlockPoint::parse).orElse(null);
        String side = Params.optString(p, "side").orElse("front");
        if (!side.equals("front") && !side.equals("back")) throw BotErrors.invalid("side must be front or back");
        return new Sign(block, side.equals("front"), List.copyOf(lines));
    }

    private static Book book(JsonObject p) {
        List<String> pages = Params.strings(p, "pages");
        if (pages.size() > 100) throw BotErrors.invalid("a book has at most 100 pages");
        for (String page : pages) limited(page, 1024, "a page");
        String title = Params.optString(p, "title").orElse(null);
        if (title != null) limited(title, 32, "title");
        int slot = Params.optInt(p, "slot").orElse(-1);
        if (slot != -1 && slot != 40 && (slot < 0 || slot > 8)) throw BotErrors.invalid("slot is a hotbar slot 0-8, or 40 for the off hand");
        return new Book(slot, List.copyOf(pages), title);
    }

    private static Dialog dialog(JsonObject p) {
        Map<String, String> inputs = new LinkedHashMap<>();
        Optional<JsonObject> in = Params.optObject(p, "inputs");
        if (in.isPresent()) {
            for (Map.Entry<String, JsonElement> e : in.get().entrySet()) inputs.put(e.getKey(), e.getValue().getAsString());
        }
        return new Dialog(Params.optString(p, "button").orElse(null), inputs);
    }

    private static ChatClick chatClick(JsonObject p) {
        Long id = Params.optLong(p, "message").orElse(null);
        String match = Params.optString(p, "match").orElse(null);
        if (id == null && match == null) throw BotErrors.invalid("chat_click needs `message` (an id from messages) or `match` (text)");
        return new ChatClick(id, match, Params.clampedInt(p, "part", 0, 0, 64));
    }

    private static String limited(String s, int max, String what) {
        if (s.length() > max) throw BotErrors.invalid(what + " is longer than " + max + " characters");
        return s;
    }
}
