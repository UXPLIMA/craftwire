package com.uxplima.craftwire.bot.gui;

import com.uxplima.craftwire.bot.model.BotErrors;
import java.util.Locale;

/** Raw slot indices of the player's own inventory menu (InventoryMenu, container 0), as a player sees it with E. */
public final class PlayerSlots {
    public static final int CRAFT_RESULT = 0;
    public static final int HEAD = 5;
    public static final int CHEST = 6;
    public static final int LEGS = 7;
    public static final int FEET = 8;
    public static final int MAIN_START = 9;
    public static final int HOTBAR_START = 36;
    public static final int HOTBAR_END = 44;
    public static final int OFFHAND = 45;
    public static final int COUNT = 46;

    private PlayerSlots() {}

    public static boolean hotbar(int raw) {
        return raw >= HOTBAR_START && raw <= HOTBAR_END;
    }

    /** head, chest, legs, feet to their raw slot. */
    public static int armor(String part) {
        return switch (part.toLowerCase(Locale.ROOT)) {
            case "head", "helmet" -> HEAD;
            case "chest", "chestplate" -> CHEST;
            case "legs", "leggings" -> LEGS;
            case "feet", "boots" -> FEET;
            default -> throw BotErrors.invalid("to must be hand, offhand, head, chest, legs or feet, not " + part);
        };
    }
}
