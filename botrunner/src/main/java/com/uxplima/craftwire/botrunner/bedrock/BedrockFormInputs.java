package com.uxplima.craftwire.botrunner.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.uxplima.craftwire.core.AgentError;
import java.util.Locale;
import java.util.Map;

/**
 * Custom form elements (label, header, divider, input, toggle, slider, step_slider, dropdown) as dialog inputs, and
 * the response the client sends: a JSON array with one value per element, null for the ones without a value.
 * Values not given keep the element's default, as an untouched form would send.
 */
final class BedrockFormInputs {
    private BedrockFormInputs() {}

    static JsonObject json(int index, JsonObject el) {
        String type = BedrockForms.str(el, "type");
        JsonObject o = new JsonObject();
        o.addProperty("key", Integer.toString(index));
        o.addProperty("label", BedrockHud.plain(BedrockForms.str(el, "text")));
        switch (type) {
            case "input" -> {
                o.addProperty("type", "text");
                o.addProperty("initial", BedrockForms.str(el, "default"));
                o.addProperty("placeholder", BedrockForms.str(el, "placeholder"));
            }
            case "toggle" -> {
                o.addProperty("type", "boolean");
                o.addProperty("initial", el.has("default") && el.get("default").getAsBoolean());
            }
            case "slider" -> {
                o.addProperty("type", "number");
                o.addProperty("start", number(el, "min", 0));
                o.addProperty("end", number(el, "max", 0));
                o.addProperty("step", number(el, "step", 1));
                o.addProperty("initial", number(el, "default", number(el, "min", 0)));
            }
            case "step_slider", "dropdown" -> {
                o.addProperty("type", "option");
                JsonArray opts = new JsonArray();
                String key = type.equals("dropdown") ? "options" : "steps";
                JsonArray list = el.has(key) ? el.getAsJsonArray(key) : new JsonArray();
                for (JsonElement e : list) opts.add(BedrockHud.plain(e.getAsString()));
                o.add("options", opts);
                o.addProperty("initial", (int) number(el, "default", 0));
            }
            default -> o.addProperty("type", type.equals("header") || type.equals("divider") ? "label" : type.isEmpty() ? "label" : type);
        }
        return o;
    }

    /** The ModalFormResponse data for a custom form: element values in element order. */
    static String response(JsonArray content, Map<String, String> inputs) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < content.size(); i++) {
            JsonObject el = content.get(i).getAsJsonObject();
            String given = inputs.get(Integer.toString(i));
            out.add(value(el, given));
        }
        return out.toString();
    }

    private static JsonElement value(JsonObject el, String given) {
        String type = BedrockForms.str(el, "type");
        return switch (type) {
            case "input" -> new JsonPrimitive(given != null ? given : BedrockForms.str(el, "default"));
            case "toggle" -> new JsonPrimitive(given != null ? Boolean.parseBoolean(given) : el.has("default") && el.get("default").getAsBoolean());
            case "slider" -> new JsonPrimitive(given != null ? parse(given, "slider") : number(el, "default", number(el, "min", 0)));
            case "step_slider", "dropdown" -> new JsonPrimitive(given != null ? option(el, type, given) : (int) number(el, "default", 0));
            default -> JsonNull.INSTANCE;
        };
    }

    /** An option by its text or its index. */
    private static int option(JsonObject el, String type, String given) {
        JsonArray list = el.getAsJsonArray(type.equals("dropdown") ? "options" : "steps");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (BedrockHud.plain(list.get(i).getAsString()).toLowerCase(Locale.ROOT).equals(given.toLowerCase(Locale.ROOT))) return i;
            }
        }
        return (int) parse(given, type);
    }

    private static double parse(String s, String what) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new AgentError("INVALID_PARAMS", "'" + s + "' is not a value for a " + what, "Pass a number, or an option's text.");
        }
    }

    private static double number(JsonObject el, String k, double fallback) {
        JsonElement e = el.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : fallback;
    }
}
