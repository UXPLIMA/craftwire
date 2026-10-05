package com.uxplima.craftwire.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

public final class RpcCodec {
    public static final int PROTOCOL_VERSION = 1;

    private RpcCodec() {}

    public static String hello(String token, Hello h) {
        JsonObject params = new JsonObject();
        params.addProperty("token", token);
        params.addProperty("agentKind", h.agentKind());
        params.addProperty("agentVersion", h.agentVersion());
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.addProperty("mcVersion", h.mcVersion());
        params.addProperty("instanceName", h.instanceName());
        if (h.serverDir() != null) params.addProperty("serverDir", h.serverDir());
        if (h.pid() != null) params.addProperty("pid", h.pid());
        JsonObject o = envelope();
        o.addProperty("id", 0);
        o.addProperty("method", "hello");
        o.add("params", params);
        return Json.GSON.toJson(o);
    }

    public static String result(JsonElement id, JsonElement result) {
        JsonObject o = envelope();
        o.add("id", id);
        o.add("result", result == null ? JsonNull.INSTANCE : result);
        return Json.GSON.toJson(o);
    }

    public static String error(JsonElement id, Throwable t) {
        String code;
        String hint = null;
        String message;
        if (t instanceof AgentError e) {
            code = e.code();
            hint = e.hint();
            message = e.getMessage();
        } else if (t instanceof IllegalArgumentException) {
            code = "INVALID_PARAMS";
            message = t.getMessage();
        } else {
            code = "INTERNAL";
            message = t.toString();
        }
        JsonObject data = new JsonObject();
        data.addProperty("code", code);
        if (hint != null) data.addProperty("hint", hint);
        JsonObject err = new JsonObject();
        err.addProperty("code", -32000);
        err.addProperty("message", message == null ? code : message);
        err.add("data", data);
        JsonObject o = envelope();
        o.add("id", id);
        o.add("error", err);
        return Json.GSON.toJson(o);
    }

    public static String event(String type, JsonObject data, long time) {
        JsonObject params = new JsonObject();
        params.addProperty("type", type);
        params.add("time", new JsonPrimitive(time));
        params.add("data", data);
        JsonObject o = envelope();
        o.addProperty("method", "event");
        o.add("params", params);
        return Json.GSON.toJson(o);
    }

    private static JsonObject envelope() {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        return o;
    }
}
