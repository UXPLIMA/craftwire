package com.uxplima.craftwire.paper.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

class EventRecorderTest {
    /** Shaped like a plugin's event: some getters worth showing, some not. */
    public static final class ShopBuyEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean cancelled;
        private final int price;

        public ShopBuyEvent(int price) {
            this.price = price;
        }

        public int getPrice() { return price; }
        public String getShopName() { return "Main"; }
        public Material getItem() { return Material.DIAMOND; }
        public UUID getBuyerId() { return new UUID(0, 7); }
        public Location getWhere() { return new Location(null, 1.5, 64, -2); }
        public List<String> getTags() { return List.of("a", "b"); }
        public Object getInternal() { return new Object(); }
        public boolean isVip() { return true; }
        public String getBroken() { throw new IllegalStateException("not now"); }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean c) { cancelled = c; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    @Test
    void snapshotsTheEventsOwnGettersAsReadableValues() {
        ShopBuyEvent e = new ShopBuyEvent(30);
        e.setCancelled(true);
        JsonObject r = EventSnapshot.of(e, 1234L);
        assertEquals("ShopBuyEvent", r.get("type").getAsString());
        assertEquals(ShopBuyEvent.class.getName(), r.get("class").getAsString());
        assertEquals(1234L, r.get("time").getAsLong());
        assertTrue(r.get("cancelled").getAsBoolean());
        JsonObject f = r.getAsJsonObject("fields");
        assertEquals(30, f.get("price").getAsInt());
        assertEquals("Main", f.get("shopName").getAsString());
        assertEquals("DIAMOND", f.get("item").getAsString());
        assertEquals("00000000-0000-0000-0000-000000000007", f.get("buyerId").getAsString());
        assertEquals("1.5,64.0,-2.0", f.get("where").getAsString());
        assertEquals("[a, b]", f.get("tags").getAsString());
        assertTrue(f.get("vip").getAsBoolean());
        assertFalse(f.has("internal"), "opaque objects are left out");
        assertFalse(f.has("broken"), "a getter that throws is left out");
        assertFalse(f.has("handlers") || f.has("eventName") || f.has("cancelled"), "Event plumbing is not a field");
    }

    @Test
    void countsEveryEventButKeepsDetailsWithinTheRateLimit() {
        long[] now = {10_000};
        EventRecorder r = new EventRecorder(100, 3, () -> now[0]);
        for (int i = 0; i < 10; i++) r.record(new ShopBuyEvent(i));
        now[0] += 1000;
        r.record(new ShopBuyEvent(99));

        JsonArray counts = r.summary(0).getAsJsonArray("counts");
        assertEquals(1, counts.size());
        assertEquals(11, counts.get(0).getAsJsonObject().get("count").getAsInt());
        JsonArray stored = r.query(new EventRecorder.Query(null, null, 0, 100, false)).getAsJsonArray("events");
        assertEquals(4, stored.size(), "3 in the first second, 1 in the next");
        assertEquals(7, r.summary(0).get("detailsDropped").getAsInt());
    }

    @Test
    void queriesByTypeTimeAndCancellation() {
        long[] now = {0};
        EventRecorder r = new EventRecorder(100, 100, () -> now[0]);
        now[0] = 100;
        r.record(new ShopBuyEvent(1));
        now[0] = 200;
        ShopBuyEvent c = new ShopBuyEvent(2);
        c.setCancelled(true);
        r.record(c);

        assertEquals(1, r.query(new EventRecorder.Query("shopbuyevent", null, 150, 100, false)).getAsJsonArray("events").size());
        JsonArray cancelled = r.query(new EventRecorder.Query("ShopBuy", null, 0, 100, true)).getAsJsonArray("events");
        assertEquals(1, cancelled.size());
        assertEquals(2, cancelled.get(0).getAsJsonObject().getAsJsonObject("fields").get("price").getAsInt());
        assertEquals(0, r.query(new EventRecorder.Query("PlayerJoinEvent", null, 0, 100, false)).getAsJsonArray("events").size());
        JsonObject summary = r.summary(150);
        assertEquals(1, summary.getAsJsonArray("counts").get(0).getAsJsonObject().get("cancelled").getAsInt());
    }

    @Test
    void theRingBufferKeepsTheNewest() {
        long[] now = {0};
        EventRecorder r = new EventRecorder(5, 100, () -> now[0]++);
        for (int i = 0; i < 8; i++) r.record(new ShopBuyEvent(i));
        JsonArray events = r.query(new EventRecorder.Query(null, null, 0, 100, false)).getAsJsonArray("events");
        assertEquals(5, events.size());
        assertEquals(3, events.get(0).getAsJsonObject().getAsJsonObject("fields").get("price").getAsInt());
    }
}
