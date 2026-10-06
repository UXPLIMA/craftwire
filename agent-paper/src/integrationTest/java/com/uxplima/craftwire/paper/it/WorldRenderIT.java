package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** world.render: images of the world drawn on the server, checked pixel by pixel against a scene built for it. */
class WorldRenderIT {
    static ItHub hub;
    // The flat test world: grass at y=-61. The scene: a stone pillar at (105, -60..-58, 105), water at x=110.
    static final int GRASS_NORMAL = shade(0x7FB238, 220);
    static final int STONE_HIGH = 0x707070;

    static int shade(int rgb, int level) {
        return ((rgb >> 16 & 0xFF) * level / 255) << 16 | ((rgb >> 8 & 0xFF) * level / 255) << 8 | (rgb & 0xFF) * level / 255;
    }

    @BeforeAll
    static void build() throws Exception {
        hub = ItEnv.get().hub;
        // /fill fails as a whole when any chunk of the area is not loaded.
        command("minecraft:forceload add 96 96 127 127");
        command("minecraft:fill 100 -60 100 115 -50 115 minecraft:air");
        command("minecraft:fill 100 -61 100 115 -61 115 minecraft:grass_block");
        command("minecraft:fill 105 -60 105 105 -58 105 minecraft:stone");
        command("minecraft:fill 110 -61 100 110 -61 115 minecraft:water");
        hub.result("bot.spawn", "{\"names\":[\"Painter\"],\"location\":{\"x\":108.5,\"y\":-60,\"z\":108.5}}");
    }

    @AfterAll
    static void cleanUp() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
        command("minecraft:fill 100 -60 100 115 -50 115 minecraft:air");
        command("minecraft:fill 100 -61 100 115 -61 115 minecraft:grass_block");
        command("minecraft:forceload remove 96 96 127 127");
    }

    static void command(String c) throws Exception {
        JsonObject r = hub.result("server.command", "{\"command\":\"" + c + "\"}").getAsJsonObject();
        assertTrue(r.get("success").getAsBoolean(), c + ": " + r);
    }

    static JsonObject render(String json) throws Exception {
        return hub.result("world.render", json).getAsJsonObject();
    }

    static BufferedImage image(JsonObject r) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(r.get("data").getAsString())));
    }

    /** The pixel at the centre of block (x, z) in a top or slice render. */
    static int at(JsonObject r, BufferedImage img, int x, int z) {
        int scale = r.get("scale").getAsInt();
        int ox = r.getAsJsonObject("origin").get("x").getAsInt(), oy = r.getAsJsonObject("origin").get("y").getAsInt();
        JsonObject rg = r.getAsJsonObject("region");
        return img.getRGB(ox + (x - rg.get("x1").getAsInt()) * scale + scale / 2, oy + (z - rg.get("z1").getAsInt()) * scale + scale / 2) & 0xFFFFFF;
    }

    @Test
    void theTopViewLooksLikeAMap() throws Exception {
        JsonObject r = render("{\"x1\":100,\"z1\":100,\"x2\":115,\"z2\":115,\"scale\":4,\"players\":false}");
        BufferedImage img = image(r);
        assertEquals("image/png", r.get("mime").getAsString());
        assertEquals(64, img.getWidth());
        assertEquals(GRASS_NORMAL, at(r, img, 101, 101), "level grass");
        assertEquals(STONE_HIGH, at(r, img, 105, 105), "the pillar is higher than the block north of it");
        int water = at(r, img, 110, 103);
        assertTrue((water & 0xFF) > (water >> 16 & 0xFF), "water is blue: " + Integer.toHexString(water));
        assertFalse(r.has("unloadedColumns"), r.toString());
    }

    @Test
    void aSliceShowsOneHeight() throws Exception {
        JsonObject r = render("{\"view\":\"slice\",\"y\":-59,\"x1\":100,\"z1\":100,\"x2\":115,\"z2\":115,\"scale\":2,\"players\":false}");
        BufferedImage img = image(r);
        assertEquals(0x707070, at(r, img, 105, 105));
        assertEquals(0xE8ECF0, at(r, img, 101, 101), "air");
    }

    @Test
    void aSideViewShowsThePillarAboveTheGround() throws Exception {
        JsonObject r = render("{\"view\":\"side\",\"facing\":\"north\",\"x1\":100,\"z1\":100,\"x2\":115,\"z2\":115,\"y1\":-62,\"y2\":-55,\"scale\":1,\"players\":false}");
        BufferedImage img = image(r);
        assertEquals(16, img.getWidth());
        assertEquals(8, img.getHeight());
        int pillar = img.getRGB(105 - 100, -55 - (-58)) & 0xFFFFFF;   // y=-58 at column x=105
        int sky = img.getRGB(101 - 100, -55 - (-58)) & 0xFFFFFF;
        assertNotEquals(sky, pillar);
        assertEquals(0xBFD4EA, sky);
        assertTrue(r.get("orientation").getAsString().startsWith("Looking north"));
    }

    @Test
    void gridLabelsAndPlayersAndChunksThatDoNotExist() throws Exception {
        JsonObject r = render("{\"x1\":96,\"z1\":96,\"x2\":127,\"z2\":127,\"scale\":4,\"grid\":16}");
        assertTrue(r.getAsJsonObject("origin").get("x").getAsInt() > 0, "labels need a margin");
        assertTrue(r.getAsJsonArray("players").toString().contains("Painter"), r.toString());
        BufferedImage img = image(r);
        assertEquals(0xE0201C, at(r, img, 108, 108), "the bot is marked");

        JsonObject far = render("{\"x1\":200000,\"z1\":200000,\"x2\":200015,\"z2\":200015}");
        assertEquals(256, far.get("unloadedColumns").getAsInt(), "ungenerated chunks are not generated by default");
    }

    @Test
    void rejectsRegionsItWillNotDraw() throws Exception {
        assertEquals("INVALID_PARAMS", hub.error("world.render", "{\"x1\":0,\"z1\":0,\"x2\":600,\"z2\":10}").get("code").getAsString());
        assertEquals("INVALID_PARAMS", hub.error("world.render", "{\"view\":\"slice\",\"x1\":0,\"z1\":0,\"x2\":10,\"z2\":10}").get("code").getAsString());
        assertEquals("INVALID_PARAMS", hub.error("world.render", "{\"view\":\"side\",\"facing\":\"up\",\"x1\":0,\"z1\":0,\"x2\":10,\"z2\":10}").get("code").getAsString());
    }
}
