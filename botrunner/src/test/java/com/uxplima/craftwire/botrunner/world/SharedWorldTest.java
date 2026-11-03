package com.uxplima.craftwire.botrunner.world;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.bot.path.Cell;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bots of one server and dimension share one copy of the chunks and entities; it goes when the last bot lets go. */
class SharedWorldTest {
    private static final BlockClassifier STONE_IS_SOLID = new BlockClassifier() {
        @Override
        public Cell classify(int state) {
            return state == 0 ? Cell.AIR : Cell.SOLID;
        }

        @Override
        public String blockId(int state) {
            return state == 0 ? "minecraft:air" : "minecraft:stone";
        }
    };

    private static final WorldKey OVERWORLD = new WorldKey("localhost:25565", "minecraft:overworld");

    private static ChunkColumn stoneAt(int x, int y, int z) {
        ChunkColumn c = new ChunkColumn(-64, 24);
        c.set(x, y, z, 1);
        return c;
    }

    @Test
    void aChunkStaysWhileAnyBotHoldsIt() {
        WorldCache cache = new WorldCache();
        Object a = "BotA";
        Object b = "BotB";
        SharedWorld w = cache.enter(OVERWORLD, STONE_IS_SOLID);
        assertSame(w, cache.enter(OVERWORLD, STONE_IS_SOLID), "the second bot gets the same world");
        w.loadChunk(0, 0, stoneAt(3, 64, 4), a);
        w.loadChunk(0, 0, stoneAt(3, 64, 4), b);
        assertEquals(1, w.chunkCount(), "one copy for both bots");
        w.unloadChunk(0, 0, a);
        assertEquals(Cell.Kind.SOLID, w.cell(3, 64, 4).kind());
        w.unloadChunk(0, 0, b);
        assertEquals(Cell.Kind.UNKNOWN, w.cell(3, 64, 4).kind());
        assertEquals(0, w.chunkCount());
    }

    @Test
    void blocksAtNegativeCoordinatesLandInTheRightChunk() {
        SharedWorld w = new WorldCache().enter(OVERWORLD, STONE_IS_SOLID);
        w.loadChunk(-1, -1, new ChunkColumn(-64, 24), "BotA");
        w.setBlock(-1, -64, -16, 1);
        assertEquals(1, w.blockState(-1, -64, -16));
        assertEquals(0, w.blockState(-2, -64, -16));
        assertEquals(-1, w.blockState(0, -64, -16), "x = 0 is in chunk 0, which no bot has");
        w.setBlock(5, 70, 5, 1);
        assertEquals(-1, w.blockState(5, 70, 5), "updates to chunks nobody has are ignored");
    }

    @Test
    void aLeavingBotLetsGoOfEverythingAndTheLastOneDropsTheWorld() {
        WorldCache cache = new WorldCache();
        SharedWorld w = cache.enter(OVERWORLD, STONE_IS_SOLID);
        cache.enter(OVERWORLD, STONE_IS_SOLID);
        w.loadChunk(0, 0, stoneAt(0, 0, 0), "BotA");
        w.loadChunk(1, 0, stoneAt(16, 0, 0), "BotB");
        w.seeEntity(new TrackedEntity(9, UUID.randomUUID(), "minecraft:zombie", 1, 2, 3), "BotA");
        cache.leave(w, "BotA");
        assertNull(w.chunk(0, 0));
        assertNotNull(w.chunk(1, 0));
        assertNull(w.entity(9));
        cache.leave(w, "BotB");
        assertNotSame(w, cache.enter(OVERWORLD, STONE_IS_SOLID), "an empty world is dropped; the next bot starts fresh");
    }

    @Test
    void oneBotAppliesRelativeMovesAndTheNextTakesOver() {
        SharedWorld w = new WorldCache().enter(OVERWORLD, STONE_IS_SOLID);
        TrackedEntity zombie = new TrackedEntity(9, UUID.randomUUID(), "minecraft:zombie", 1, 2, 3);
        assertSame(zombie, w.seeEntity(zombie, "BotA"));
        TrackedEntity again = w.seeEntity(new TrackedEntity(9, zombie.uuid(), "minecraft:zombie", 1, 2, 3), "BotB");
        assertSame(zombie, again, "the second bot shares the first bot's entity");
        assertEquals("BotA", w.mover(9));
        w.forgetEntity(9, "BotA");
        assertEquals("BotB", w.mover(9));
        w.forgetEntity(9, "BotB");
        assertNull(w.entity(9));
        assertNull(w.mover(9));
    }

    @Test
    void emptySectionsCostNothing() {
        ChunkColumn c = new ChunkColumn(-64, 24);
        c.set(0, 0, 0, 0);
        assertEquals(0, c.bytes());
        c.set(0, 0, 0, 5);
        assertEquals(16 * 16 * 16 * 2, c.bytes());
        c.section(4, new char[16 * 16 * 16]);
        assertEquals(0, c.bytes(), "an all-air section from a chunk packet clears it");
        assertEquals(0, c.get(0, 400, 0), "above the column is air");
    }
}
