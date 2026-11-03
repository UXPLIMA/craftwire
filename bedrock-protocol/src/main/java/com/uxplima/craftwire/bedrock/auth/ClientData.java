package com.uxplima.craftwire.bedrock.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The client data token's claims: what a Bedrock client says about its device, input, language and skin. The bot
 * presents itself as an Android phone with touch input (the client gophertunnel and most bot libraries imitate),
 * since servers and anti-cheats treat that profile as ordinary.
 *
 * @param language  Bedrock's form, e.g. en_US, tr_TR
 * @param address   the address the player typed, host:port
 */
public record ClientData(String name, String language, String address, String gameVersion, int viewDistance) {
    /** DeviceOS 1: Android. */
    private static final int ANDROID = 1;
    /** InputMode 2: touch. */
    private static final int TOUCH = 2;

    /** Java-style en_us to Bedrock's en_US. */
    public static String language(String javaLocale) {
        int i = javaLocale.indexOf('_');
        if (i < 0) return javaLocale;
        return javaLocale.substring(0, i).toLowerCase(Locale.ROOT) + "_" + javaLocale.substring(i + 1).toUpperCase(Locale.ROOT);
    }

    public JsonObject json(String playFabId) {
        JsonObject o = new JsonObject();
        o.add("AnimatedImageData", new JsonArray());
        o.addProperty("ArmSize", "wide");
        o.addProperty("CapeData", "");
        o.addProperty("CapeId", "");
        o.addProperty("CapeImageHeight", 0);
        o.addProperty("CapeImageWidth", 0);
        o.addProperty("CapeOnClassicSkin", false);
        o.addProperty("ClientRandomId", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
        o.addProperty("ClientEditorConnectionIntent", 0);
        o.addProperty("ClientIsEditorCapable", false);
        o.addProperty("CompatibleWithClientSideChunkGen", false);
        o.addProperty("CurrentInputMode", TOUCH);
        o.addProperty("DefaultInputMode", TOUCH);
        o.addProperty("DeviceId", UUID.randomUUID().toString().replace("-", ""));
        o.addProperty("DeviceModel", "");
        o.addProperty("DeviceOS", ANDROID);
        o.addProperty("FilterProfanity", false);
        o.addProperty("GameVersion", gameVersion);
        o.addProperty("GraphicsMode", 0);
        o.addProperty("GuiScale", 0);
        o.addProperty("IsPartyLeader", false);
        o.addProperty("LanguageCode", language);
        o.addProperty("MaxViewDistance", viewDistance);
        o.addProperty("MemoryTier", 0);
        o.addProperty("OverrideSkin", false);
        o.addProperty("PartyId", "");
        o.add("PersonaPieces", new JsonArray());
        o.addProperty("PersonaSkin", false);
        o.add("PieceTintColors", new JsonArray());
        o.addProperty("PlatformOfflineId", "");
        o.addProperty("PlatformOnlineId", "");
        o.addProperty("PlatformType", 0);
        o.addProperty("PlayFabId", playFabId);
        o.addProperty("PremiumSkin", false);
        o.addProperty("ProfileHash", "");
        o.addProperty("SelfSignedId", UUID.randomUUID().toString());
        o.addProperty("ServerAddress", address);
        o.addProperty("SkinAnimationData", "");
        o.addProperty("SkinColor", "#0");
        o.addProperty("SkinData", SkinModel.textureBase64());
        o.addProperty("SkinGeometryData", SkinModel.geometryBase64());
        o.addProperty("SkinGeometryDataEngineVersion", SkinModel.engineVersionBase64());
        o.addProperty("SkinId", UUID.randomUUID().toString());
        o.addProperty("SkinImageHeight", SkinModel.HEIGHT);
        o.addProperty("SkinImageWidth", SkinModel.WIDTH);
        o.addProperty("SkinResourcePatch", SkinModel.resourcePatchBase64());
        o.addProperty("ThirdPartyName", name);
        o.addProperty("TrustedSkin", false);
        o.addProperty("UIProfile", 0);
        return o;
    }

    /** A random PlayFab id (16 hex digits), as offline clients make one up. */
    public static String randomPlayFabId() {
        byte[] b = new byte[8];
        ThreadLocalRandom.current().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
