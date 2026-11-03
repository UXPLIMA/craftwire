package com.uxplima.craftwire.bot.model;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import java.util.Locale;
import java.util.Set;

/**
 * What a bot tells the server about its client: the vanilla client-information fields plus the brand, a simulated
 * ping and a skin. Plugins read these (locale for translations, brand for client checks, main hand, skin parts).
 *
 * @param locale        e.g. en_us, tr_tr (lowercase, as the vanilla client sends it)
 * @param brand         the minecraft:brand payload, e.g. vanilla, fabric, lunarclient
 * @param pingMs        simulated latency: tab-list latency and, for protocol bots, delayed handling
 * @param skinUrl       a skin texture URL (textures.minecraft.net) shown to other players, or null
 * @param mainHand      left or right
 * @param chatVisibility full, system or hidden
 * @param skinParts     the displayed skin parts bit mask (0x7F = all)
 * @param viewDistance  the requested view distance in chunks; 0 = the profile's default
 */
public record ClientSettings(String locale, String brand, int pingMs, String skinUrl, String mainHand, String chatVisibility,
                             int skinParts, int viewDistance) {
    private static final Set<String> HANDS = Set.of("left", "right");
    private static final Set<String> CHAT = Set.of("full", "system", "hidden");

    public static final ClientSettings DEFAULT = new ClientSettings("en_us", "vanilla", 0, null, "right", "full", 0x7F, 0);

    /** `settings` {locale?, brand?, ping?, skin?, mainHand?, chat?, skinParts?, viewDistance?}; absent fields keep the default. */
    public static ClientSettings parse(JsonObject p) {
        if (p == null) return DEFAULT;
        String locale = Params.optString(p, "locale").map(s -> s.toLowerCase(Locale.ROOT).replace('-', '_')).orElse(DEFAULT.locale);
        if (!locale.matches("[a-z]{2,3}_[a-z0-9]{2,4}")) throw BotErrors.invalid("locale looks like en_us or tr_tr, not " + locale);
        String hand = Params.optString(p, "mainHand").orElse(DEFAULT.mainHand);
        if (!HANDS.contains(hand)) throw BotErrors.invalid("mainHand must be left or right");
        String chat = Params.optString(p, "chat").orElse(DEFAULT.chatVisibility);
        if (!CHAT.contains(chat)) throw BotErrors.invalid("chat must be full, system or hidden");
        String brand = Params.optString(p, "brand").orElse(DEFAULT.brand);
        if (brand.isEmpty() || brand.length() > 64) throw BotErrors.invalid("brand must be 1-64 characters");
        return new ClientSettings(locale, brand,
                Params.clampedInt(p, "ping", 0, 0, 10_000),
                Params.optString(p, "skin").orElse(null),
                hand, chat,
                Params.clampedInt(p, "skinParts", DEFAULT.skinParts, 0, 0x7F),
                Params.clampedInt(p, "viewDistance", 0, 0, 32));
    }

    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("locale", locale);
        o.addProperty("brand", brand);
        o.addProperty("ping", pingMs);
        if (skinUrl != null) o.addProperty("skin", skinUrl);
        o.addProperty("mainHand", mainHand);
        o.addProperty("chat", chatVisibility);
        o.addProperty("skinParts", skinParts);
        if (viewDistance > 0) o.addProperty("viewDistance", viewDistance);
        return o;
    }
}
