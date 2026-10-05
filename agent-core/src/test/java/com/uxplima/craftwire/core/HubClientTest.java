package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HubClientTest {
    static final String TOKEN = "a".repeat(64);
    TestHub hub;
    HubClient client;
    final Dispatcher dispatcher = new Dispatcher(new OperationCache(300_000, System::currentTimeMillis));
    final CountDownLatch connected = new CountDownLatch(1);
    final CountDownLatch disconnected = new CountDownLatch(1);
    final HubClient.Listener listener = new HubClient.Listener() {
        @Override public void onConnected(String id) { connected.countDown(); }
        @Override public void onDisconnected() { disconnected.countDown(); }
        @Override public void onLog(String m) {}
    };

    HubClient clientFor(AtomicReference<HubConfig> cfg) {
        client = new HubClient(() -> Optional.ofNullable(cfg.get()), () -> new Hello("client", "0.1.0", "26.2", "Tester"),
                dispatcher, listener, new Backoff(50, 200));
        client.start();
        return client;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
        if (hub != null) hub.stop(500);
    }

    @Test
    void sendsHelloWithTokenAndProtocol() throws Exception {
        int port = TestHub.freePort();
        hub = new TestHub(port, TOKEN).startAndWait();
        clientFor(new AtomicReference<>(new HubConfig(port, TOKEN)));
        JsonObject hello = hub.next();
        assertEquals("hello", hello.get("method").getAsString());
        assertEquals(TOKEN, hello.getAsJsonObject("params").get("token").getAsString());
        assertEquals(1, hello.getAsJsonObject("params").get("protocolVersion").getAsInt());
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        assertTrue(client.isConnected());
    }

    @Test
    void answersRequestsThroughDispatcher() throws Exception {
        dispatcher.register("echo", p -> CompletableFuture.completedFuture(p));
        dispatcher.register("fail", p -> { throw new AgentError("NO_SCREEN_OPEN", "none", "open one"); });
        int port = TestHub.freePort();
        hub = new TestHub(port, TOKEN).startAndWait();
        clientFor(new AtomicReference<>(new HubConfig(port, TOKEN)));
        hub.next();
        assertTrue(connected.await(5, TimeUnit.SECONDS));

        JsonObject params = new JsonObject();
        params.addProperty("a", 1);
        hub.sendRequest(5, "echo", params);
        JsonObject res = hub.next();
        assertEquals(5, res.get("id").getAsInt());
        assertEquals(1, res.getAsJsonObject("result").get("a").getAsInt());

        hub.sendRequest(6, "fail", new JsonObject());
        JsonObject err = hub.next();
        assertEquals("NO_SCREEN_OPEN", err.getAsJsonObject("error").getAsJsonObject("data").get("code").getAsString());
        assertEquals("open one", err.getAsJsonObject("error").getAsJsonObject("data").get("hint").getAsString());
    }

    @Test
    void notifiesEventsOnlyWhenConnected() throws Exception {
        int port = TestHub.freePort();
        hub = new TestHub(port, TOKEN).startAndWait();
        clientFor(new AtomicReference<>(new HubConfig(port, TOKEN)));
        hub.next();
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        JsonObject data = new JsonObject();
        data.addProperty("text", "hi");
        client.notifyEvent("chat", data);
        JsonObject ev = hub.next();
        assertEquals("event", ev.get("method").getAsString());
        assertEquals("chat", ev.getAsJsonObject("params").get("type").getAsString());
    }

    @Test
    void followsHubJsonToANewHub() throws Exception {
        int port1 = TestHub.freePort();
        hub = new TestHub(port1, TOKEN).startAndWait();
        AtomicReference<HubConfig> cfg = new AtomicReference<>(new HubConfig(port1, TOKEN));
        clientFor(cfg);
        hub.next();
        assertTrue(connected.await(5, TimeUnit.SECONDS));

        int port2 = TestHub.freePort();
        String token2 = "b".repeat(64);
        TestHub second = new TestHub(port2, token2).startAndWait();
        cfg.set(new HubConfig(port2, token2));     // a second Claude session rewrote hub.json
        hub.stop(500);
        hub = second;
        assertTrue(disconnected.await(5, TimeUnit.SECONDS));
        JsonObject hello = hub.next();
        assertEquals(token2, hello.getAsJsonObject("params").get("token").getAsString());
    }

    @Test
    void waitsQuietlyWithoutHubJson() throws Exception {
        clientFor(new AtomicReference<>(null));
        Thread.sleep(300);
        assertFalse(client.isConnected());
    }
}
