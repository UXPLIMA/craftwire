package com.uxplima.craftwire.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/** Minimal hub stand-in: accepts hello with the right token and records every message. */
final class TestHub extends WebSocketServer {
    final BlockingQueue<JsonObject> inbox = new LinkedBlockingQueue<>();
    final String token;
    private final CountDownLatch started = new CountDownLatch(1);
    volatile WebSocket conn;

    TestHub(int port, String token) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.token = token;
        setReuseAddr(true);
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    TestHub startAndWait() throws InterruptedException {
        start();
        started.await(5, TimeUnit.SECONDS);
        return this;
    }

    JsonObject next() throws InterruptedException {
        JsonObject o = inbox.poll(5, TimeUnit.SECONDS);
        if (o == null) throw new AssertionError("no message within 5s");
        return o;
    }

    void sendRequest(int id, String method, JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        o.addProperty("method", method);
        o.add("params", params);
        conn.send(o.toString());
    }

    @Override public void onOpen(WebSocket c, ClientHandshake h) { conn = c; }
    @Override public void onClose(WebSocket c, int code, String reason, boolean remote) {}
    @Override public void onError(WebSocket c, Exception ex) {}
    @Override public void onStart() { started.countDown(); }

    @Override
    public void onMessage(WebSocket c, String message) {
        JsonObject o = JsonParser.parseString(message).getAsJsonObject();
        inbox.add(o);
        if (o.has("method") && "hello".equals(o.get("method").getAsString())) {
            boolean ok = token.equals(o.getAsJsonObject("params").get("token").getAsString());
            c.send(ok
                    ? "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"instanceId\":\"client-1\"}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":0,\"error\":{\"code\":-32001,\"message\":\"Invalid token\",\"data\":{\"code\":\"UNAUTHORIZED\"}}}");
            if (!ok) c.close(4001, "UNAUTHORIZED");
        }
    }
}
