package com.uxplima.craftwire.paper.it;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/** Stands in for the craftwire hub: welcomes the agent, sends requests, records events. */
final class ItHub extends WebSocketServer {
    static final String TOKEN = "c".repeat(64);

    final List<JsonObject> events = new CopyOnWriteArrayList<>();
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger(1);
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile CompletableFuture<JsonObject> hello = new CompletableFuture<>();
    private volatile WebSocket conn;

    ItHub(int port) {
        super(new InetSocketAddress("127.0.0.1", port));
        setReuseAddr(true);
    }

    void startAndWait() throws InterruptedException {
        start();
        if (!started.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test hub did not start");
    }

    JsonObject awaitHello(long timeoutMs, BooleanSupplier serverAlive) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (hello.isDone()) return hello.get();
            if (!serverAlive.getAsBoolean()) throw new AssertionError("Paper exited before the agent connected");
            Thread.sleep(200);
        }
        throw new AssertionError("agent did not connect within " + timeoutMs + " ms");
    }

    JsonObject call(String method, String paramsJson, long timeoutMs) throws Exception {
        return send(method, paramsJson).get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Sends a request without waiting for it: for calls that block until something else happens. */
    CompletableFuture<JsonObject> send(String method, String paramsJson) {
        int id = ids.getAndIncrement();
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        pending.put(id, f);
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", id);
        req.addProperty("method", method);
        req.add("params", JsonParser.parseString(paramsJson));
        conn.send(req.toString());
        return f;
    }

    JsonElement result(String method, String paramsJson) throws Exception {
        JsonObject r = call(method, paramsJson, 120_000);
        if (r.has("error")) throw new AssertionError(method + " failed: " + r.get("error"));
        return r.get("result");
    }

    /** The {code, hint, message} of an expected error. */
    JsonObject error(String method, String paramsJson) throws Exception {
        JsonObject r = call(method, paramsJson, 120_000);
        if (!r.has("error")) throw new AssertionError(method + " unexpectedly succeeded: " + r.get("result"));
        JsonObject e = r.getAsJsonObject("error");
        JsonObject out = e.getAsJsonObject("data").deepCopy();
        out.add("message", e.get("message"));
        return out;
    }

    JsonObject awaitEvent(Predicate<JsonObject> match, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            for (JsonObject e : events) if (match.test(e)) return e;
            Thread.sleep(50);
        }
        throw new AssertionError("no matching event within " + timeoutMs + " ms; last events: "
                + events.subList(Math.max(0, events.size() - 10), events.size()));
    }

    JsonObject awaitLog(Predicate<String> message, long timeoutMs) throws InterruptedException {
        return awaitEvent(e -> "log".equals(e.get("type").getAsString())
                && message.test(e.getAsJsonObject("data").get("message").getAsString()), timeoutMs);
    }

    /** Simulates a hub restart: the agent must reconnect and start a fresh session. */
    void dropConnectionAndClearEvents() {
        events.clear();
        hello = new CompletableFuture<>();
        WebSocket c = conn;
        if (c != null) c.close(1001, "test reconnect");
    }

    @Override public void onStart() { started.countDown(); }
    @Override public void onOpen(WebSocket c, ClientHandshake h) { conn = c; }
    @Override public void onClose(WebSocket c, int code, String reason, boolean remote) {}
    @Override public void onError(WebSocket c, Exception e) {}

    @Override
    public void onMessage(WebSocket c, String text) {
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        String method = o.has("method") ? o.get("method").getAsString() : null;
        if ("hello".equals(method)) {
            c.send("{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"instanceId\":\"server-1\"}}");
            hello.complete(o.getAsJsonObject("params"));
        } else if ("event".equals(method)) {
            events.add(o.getAsJsonObject("params"));
        } else if (o.has("id")) {
            CompletableFuture<JsonObject> f = pending.remove(o.get("id").getAsInt());
            if (f != null) f.complete(o);
        }
    }
}
