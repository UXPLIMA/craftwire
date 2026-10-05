package com.uxplima.craftwire.paper.world;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoxTest {
    @Test
    void normalisesCornersAndCountsBlocks() {
        Box b = Box.of(5, 10, 5, 1, 2, 3);
        assertEquals(new Box(1, 2, 3, 5, 10, 5), b);
        assertEquals(5 * 9 * 3, b.volume());
    }

    @Test
    void chunksCoverNegativeCoordinatesInXThenZOrder() {
        List<int[]> chunks = Box.of(-1, 0, -1, 16, 0, 0).chunks();
        assertEquals(List.of("-1,-1", "-1,0", "0,-1", "0,0", "1,-1", "1,0"),
                chunks.stream().map(c -> c[0] + "," + c[1]).toList());
    }

    @Test
    void clipsToOneChunk() {
        Box b = Box.of(10, 0, 10, 20, 5, 20);
        assertEquals(new Box(10, 0, 10, 15, 5, 15), b.clipToChunk(0, 0));
        assertEquals(new Box(16, 0, 16, 20, 5, 20), b.clipToChunk(1, 1));
        assertNull(b.clipToChunk(2, 0));
    }

    @Test
    void clampsToBuildHeight() {
        assertEquals(new Box(0, -64, 0, 0, 319, 0), Box.of(0, -100, 0, 0, 400, 0).clampY(-64, 319));
        assertNull(Box.of(0, 500, 0, 0, 600, 0).clampY(-64, 319));
    }

    @Test
    void readsMinAndMaxFromJson() {
        Box b = Box.from(JsonParser.parseString("{\"min\":{\"x\":3,\"y\":2,\"z\":1},\"max\":{\"x\":0,\"y\":0,\"z\":0}}").getAsJsonObject());
        assertEquals(new Box(0, 0, 0, 3, 2, 1), b);
        assertEquals(b, Box.from(b.toJson()));
        AgentError e = assertThrows(AgentError.class, () -> Box.from(JsonParser.parseString("{\"min\":{\"x\":0,\"y\":0,\"z\":0}}").getAsJsonObject()));
        assertEquals("INVALID_PARAMS", e.code());
    }
}
