package com.uxplima.craftwire.bot.gui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The container click packets each vocabulary click becomes, as AbstractContainerScreen sends them. */
class ClickPlanTest {
    private static List<RawClick> plan(String json) {
        return ClickPlan.of(Click.parse(JsonParser.parseString(json).getAsJsonObject()));
    }

    @Test
    void singleKeyClicksAreOnePacket() {
        assertEquals(List.of(RawClick.pickup(3, 0)), plan("{slot:3}"));
        assertEquals(List.of(RawClick.pickup(3, 1)), plan("{click:'right',slot:3}"));
        assertEquals(List.of(new RawClick(3, 0, ContainerMode.QUICK_MOVE)), plan("{click:'shift',slot:3}"));
        // number key 1 is hotbar index 0; F is the off hand (Inventory.SLOT_OFFHAND = 40)
        assertEquals(List.of(new RawClick(3, 0, ContainerMode.SWAP)), plan("{click:'number',slot:3,key:1}"));
        assertEquals(List.of(new RawClick(3, 40, ContainerMode.SWAP)), plan("{click:'offhand',slot:3}"));
        assertEquals(List.of(new RawClick(3, 0, ContainerMode.THROW)), plan("{click:'drop',slot:3}"));
        assertEquals(List.of(new RawClick(3, 1, ContainerMode.THROW)), plan("{click:'drop_stack',slot:3}"));
        assertEquals(List.of(new RawClick(3, 2, ContainerMode.CLONE)), plan("{click:'middle',slot:3}"));
        assertEquals(List.of(RawClick.pickup(-999, 1)), plan("{click:'outside',button:'right'}"));
    }

    @Test
    void aDoubleClickPicksUpThenCollects() {
        assertEquals(List.of(RawClick.pickup(5, 0), new RawClick(5, 0, ContainerMode.PICKUP_ALL)), plan("{click:'double',slot:5}"));
    }

    /** Quick-craft masks: header (0 start, 1 slot, 2 end) | type << 2 (0 split, 1 one each, 2 clone). */
    @Test
    void aDragIsStartEachSlotEnd() {
        assertEquals(List.of(new RawClick(-999, 0, ContainerMode.QUICK_CRAFT), new RawClick(1, 1, ContainerMode.QUICK_CRAFT),
                new RawClick(2, 1, ContainerMode.QUICK_CRAFT), new RawClick(-999, 2, ContainerMode.QUICK_CRAFT)),
                plan("{click:'drag',slots:[1,2]}"));
        assertEquals(List.of(new RawClick(-999, 4, ContainerMode.QUICK_CRAFT), new RawClick(1, 5, ContainerMode.QUICK_CRAFT),
                new RawClick(2, 5, ContainerMode.QUICK_CRAFT), new RawClick(-999, 6, ContainerMode.QUICK_CRAFT)),
                plan("{click:'drag',button:'right',slots:[1,2]}"));
        assertEquals(10, ClickPlan.mask(2, 2));
    }

    @Test
    void aDragOverOneSlotIsAPlainClick() {
        assertEquals(List.of(RawClick.pickup(7, 1)), plan("{click:'drag',button:'right',slots:[7]}"));
        assertEquals(List.of(new RawClick(7, 2, ContainerMode.CLONE)), plan("{click:'drag',button:'middle',slots:[7]}"));
    }

    @Test
    void badClicksAreRefusedWithTheReason() {
        for (String bad : List.of("{click:'number',slot:1,key:10}", "{click:'drag',slots:[]}", "{click:'drag',slots:[1,1]}",
                "{click:'outside',button:'middle'}", "{click:'wiggle',slot:1}", "{click:'left'}")) {
            JsonObject p = JsonParser.parseString(bad).getAsJsonObject();
            assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> Click.parse(p), bad).code(), bad);
        }
    }

    @Test
    void slotsAreCheckedAgainstTheMenuSize() {
        Click drag = Click.parse(JsonParser.parseString("{click:'drag',slots:[1,63]}").getAsJsonObject());
        assertEquals("SLOT_OUT_OF_RANGE", assertThrows(AgentError.class, () -> drag.check(63)).code());
        Click.parse(JsonParser.parseString("{click:'outside'}").getAsJsonObject()).check(63);
    }
}
