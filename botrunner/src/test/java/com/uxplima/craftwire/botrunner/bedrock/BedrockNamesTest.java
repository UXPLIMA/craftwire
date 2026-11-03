package com.uxplima.craftwire.botrunner.bedrock;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.bedrock.packet.ItemPackets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Menu titles from block entity names: Geyser shows a plugin's chest menu as a fake chest whose CustomName is the
 * window title, sent in BlockActorData just before ContainerOpen.
 */
class BedrockNamesTest {
    @Test
    void aMenuIsTitledByItsBlockEntitysName() {
        BedrockNames names = new BedrockNames();
        names.blockEntity(10, 65, -3, Map.of("id", "Chest", "CustomName", "§6§lDaily §rRewards"));
        BedrockInventory inv = new BedrockInventory(new BedrockItems());
        inv.opened(new ItemPackets.ContainerOpen(2, 0, 10, 65, -3, -1), "minecraft:chest", names.at(10, 65, -3));
        assertEquals("Daily Rewards", inv.title());
    }

    @Test
    void aMenuWithoutANamedBlockEntityIsTitledByItsBlock() {
        BedrockInventory inv = new BedrockInventory(new BedrockItems());
        inv.opened(new ItemPackets.ContainerOpen(2, 0, 1, 2, 3, -1), "minecraft:chest", new BedrockNames().at(1, 2, 3));
        assertEquals("Chest", inv.title());
    }

    @Test
    void anUpdateWithoutANameClearsTheOldOne() {
        BedrockNames names = new BedrockNames();
        names.blockEntity(0, 64, 0, Map.of("CustomName", "Shop"));
        names.blockEntity(0, 64, 0, Map.of("id", "Chest"));
        assertNull(names.at(0, 64, 0));
    }

    @Test
    void chunkBlockEntitiesAreFiledByTheirOwnPosition() {
        BedrockNames names = new BedrockNames();
        names.blockEntities(List.of(Map.of("x", -1, "y", -64, "z", 7, "CustomName", "Vault"), Map.of("CustomName", "no position")));
        assertEquals("Vault", names.at(-1, -64, 7));
        assertNull(names.at(1, -64, 7), "x is signed: -1 and 1 differ");
        assertEquals(1, names.size());
    }

    @Test
    void onlyTheMostRecentPositionsAreKept() {
        BedrockNames names = new BedrockNames();
        for (int i = 0; i <= BedrockNames.MAX; i++) names.blockEntity(i, 0, 0, Map.of("CustomName", "n" + i));
        assertEquals(BedrockNames.MAX, names.size());
        assertNull(names.at(0, 0, 0));
        assertEquals("n" + BedrockNames.MAX, names.at(BedrockNames.MAX, 0, 0));
    }
}
