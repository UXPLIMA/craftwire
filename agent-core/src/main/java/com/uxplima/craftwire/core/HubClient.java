package com.uxplima.craftwire.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Outbound WebSocket connection to the Craftwire hub, with handshake, dispatch and reconnect. */
public final class HubClient implements AutoCloseable {
    public interface Listener {
        void onConnected(String instanceId);

        void onDisconnected();

        void onLog(String message);
    }

    private final Supplier<Optional<HubConfig>> config;
    private final Supplier<Hello> hello;
    private final Dispatcher dispatcher;
    private final Listener listener;
    private final Backoff backoff;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "craftwire-hub");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket socket;
    private volatile String instanceId;
    private volatile boolean closed;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public HubClient(Supplier<Optional<HubConfig>> config, Supplier<Hello> hello, Dispatcher dispatcher, Listener listener) {
        this(config, hello, dispatcher, listener, new Backoff(1000, 30_000));
    }

    public HubClient(Supplier<Optional<HubConfig>> config, Supplier<Hello> hello, Dispatcher dispatcher, Listener listener, Backoff backoff) {
        this.config = config;
        this.hello = hello;
        this.dispatcher = dispatcher;
        this.listener = listener;
        this.backoff = backoff;
    }

    public void start() {
        exec.execute(this::connect);
    }

    public boolean isConnected() {
        return instanceId != null;
    }

    public void notifyEvent(String type, JsonObject data) {
        notifyEvent(type, data, System.currentTimeMillis());
    }

    public void notifyEvent(String type, JsonObject data, long time) {
        WebSocket ws = socket;
        if (ws != null && instanceId != null) send(ws, RpcCodec.event(type, data, time));
    }

    private void connect() {
        if (closed) return;
        Optional<HubConfig> cfg = config.get();
        if (cfg.isEmpty()) {
            scheduleReconnect();
            return;
        }
        HubConfig c = cfg.get();
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + c.port() + "/"), new SocketListener(c.token()))
                .whenComplete((ws, err) -> {
                    if (err != null) scheduleReconnect();
                });
    }

    private void scheduleReconnect() {
        if (closed || exec.isShutdown()) return;
        exec.schedule(this::connect, backoff.nextDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private void handle(WebSocket ws, String text) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        if (msg.has("method") && msg.has("id")) {
            JsonElement id = msg.get("id");
            String method = msg.get("method").getAsString();
            JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
            dispatcher.dispatch(method, params).whenComplete((result, err) ->
                    send(ws, err == null ? RpcCodec.result(id, result) : RpcCodec.error(id, unwrap(err))));
            return;
        }
        JsonElement id = msg.get("id");
        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber() && id.getAsInt() == 0) {
            if (msg.has("result")) {
                instanceId = msg.getAsJsonObject("result").get("instanceId").getAsString();
                backoff.reset();
                listener.onConnected(instanceId);
            } else {
                listener.onLog("Hub refused the connection: " + msg.get("error"));
            }
        }
    }

    private synchronized void send(WebSocket ws, String text) {
        sendChain = sendChain.handle((v, e) -> null).thenCompose(v -> ws.sendText(text, true));
    }

    static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
        return t;
    }

    @Override
    public void close() {
        closed = true;
        WebSocket ws = socket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        exec.shutdownNow();
    }

    private final class SocketListener implements WebSocket.Listener {
        private final String token;
        private final StringBuilder buffer = new StringBuilder();
        private boolean ended;

        SocketListener(String token) {
            this.token = token;
        }

        @Override
        public void onOpen(WebSocket ws) {
            socket = ws;
            send(ws, RpcCodec.hello(token, hello.get()));
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                handle(ws, text);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            ended(statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            ended(String.valueOf(error));
        }

        private void ended(String why) {
            if (ended) return;
            ended = true;
            socket = null;
            boolean was = instanceId != null;
            instanceId = null;
            if (was) listener.onDisconnected();
            listener.onLog("Hub connection closed: " + why);
            scheduleReconnect();
        }
    }
}
