package com.uxplima.craftwire.botrunner.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.uxplima.craftwire.bedrock.packet.UiPackets;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Server forms (ModalFormRequest: simple button lists, yes/no modals, custom forms with inputs) and NPC dialogues,
 * shown in gui_read's screen.dialog with the shape Java dialogs have (title, body, inputs by key, buttons by index)
 * and answered with dialog_answer. Custom form inputs are keyed by their element index. Runner loop thread.
 */
final class BedrockForms {
    /** What dialog_answer sends: a ModalFormResponse (formId, JSON) or an NPCRequest (button index). */
    record Answer(UiPackets.Form form, String json, UiPackets.NpcDialogue npc, int npcButton) {}

    private UiPackets.Form form;
    private JsonObject parsed;
    private UiPackets.NpcDialogue npc;

    void form(UiPackets.Form f) {
        try {
            JsonElement e = JsonParser.parseString(f.json());
            if (!e.isJsonObject()) return;
            form = f;
            parsed = e.getAsJsonObject();
        } catch (RuntimeException bad) {
            // A form whose JSON the client cannot parse is not shown by the client either.
        }
    }

    void closeAll() {
        form = null;
        parsed = null;
    }

    void npc(UiPackets.NpcDialogue d) {
        npc = d.action() == 0 ? d : null;
    }

    boolean open() {
        return form != null || npc != null;
    }

    /** The screen object for gui_read, or null with no form or dialogue open. */
    JsonObject json() {
        JsonObject dialog = form != null ? formJson() : npc != null ? npcJson() : null;
        if (dialog == null) return null;
        JsonObject o = new JsonObject();
        o.add("dialog", dialog);
        return o;
    }

    private JsonObject formJson() {
        String type = str(parsed, "type");
        JsonObject o = new JsonObject();
        o.addProperty("form", type);
        o.addProperty("title", BedrockHud.plain(str(parsed, "title")));
        JsonArray body = new JsonArray();
        if (parsed.has("content") && parsed.get("content").isJsonPrimitive()) body.add(BedrockHud.plain(str(parsed, "content")));
        o.add("body", body);
        JsonArray inputs = new JsonArray();
        JsonArray buttons = new JsonArray();
        switch (type) {
            case "modal" -> {
                buttons.add(button(0, str(parsed, "button1")));
                buttons.add(button(1, str(parsed, "button2")));
            }
            case "custom_form" -> {
                JsonArray content = parsed.has("content") && parsed.get("content").isJsonArray() ? parsed.getAsJsonArray("content") : new JsonArray();
                for (int i = 0; i < content.size(); i++) {
                    JsonObject in = BedrockFormInputs.json(i, content.get(i).getAsJsonObject());
                    if (in.get("type").getAsString().equals("label")) body.add(in.get("label").getAsString());
                    else inputs.add(in);
                }
                buttons.add(button(0, parsed.has("submit") ? str(parsed, "submit") : "Submit"));
            }
            default -> {
                JsonArray list = parsed.has("buttons") ? parsed.getAsJsonArray("buttons") : new JsonArray();
                for (int i = 0; i < list.size(); i++) buttons.add(button(i, str(list.get(i).getAsJsonObject(), "text")));
            }
        }
        o.add("inputs", inputs);
        o.add("buttons", buttons);
        o.addProperty("canClose", true);
        return o;
    }

    private JsonObject npcJson() {
        JsonObject o = new JsonObject();
        o.addProperty("form", "npc");
        o.addProperty("title", BedrockHud.plain(npc.npcName()));
        JsonArray body = new JsonArray();
        body.add(BedrockHud.plain(npc.dialogue()));
        o.add("body", body);
        o.add("inputs", new JsonArray());
        JsonArray buttons = new JsonArray();
        List<String> labels = npcButtons();
        for (int i = 0; i < labels.size(); i++) buttons.add(button(i, labels.get(i)));
        o.add("buttons", buttons);
        o.addProperty("canClose", true);
        return o;
    }

    private List<String> npcButtons() {
        List<String> out = new ArrayList<>();
        try {
            JsonElement e = JsonParser.parseString(npc.actionsJson());
            if (!e.isJsonArray()) return out;
            for (JsonElement a : e.getAsJsonArray()) {
                if (a.isJsonObject()) out.add(BedrockHud.plain(str(a.getAsJsonObject(), "button_name")));
            }
        } catch (RuntimeException bad) {
            // NPC actions the client cannot parse show no buttons.
        }
        return out;
    }

    /** The answer to dialog_answer: `button` is a label or index, `inputs` the custom form's values by element index. */
    Answer answer(String button, Map<String, String> inputs) {
        if (form != null) {
            String json = switch (str(parsed, "type")) {
                case "modal" -> Boolean.toString(pick(button, 2, i -> str(parsed, i == 0 ? "button1" : "button2")) == 0);
                case "custom_form" -> BedrockFormInputs.response(parsed.getAsJsonArray("content"), inputs);
                default -> Integer.toString(pick(button, parsed.getAsJsonArray("buttons").size(),
                        i -> str(parsed.getAsJsonArray("buttons").get(i).getAsJsonObject(), "text")));
            };
            Answer a = new Answer(form, json, null, -1);
            closeAll();
            return a;
        }
        if (npc != null) {
            List<String> labels = npcButtons();
            Answer a = new Answer(null, null, npc, pick(button, labels.size(), labels::get));
            npc = null;
            return a;
        }
        throw new AgentError("NO_DIALOG", "No form or NPC dialogue is open", "Wait for the server to show one (gui_read shows screen.dialog).");
    }

    /** bot_form's `values`, one per input of the open custom form in order, as dialog inputs keyed by element index. */
    Map<String, String> inputs(List<String> values) {
        Map<String, String> out = new HashMap<>();
        if (form == null || !"custom_form".equals(str(parsed, "type")) || !parsed.has("content")) return out;
        JsonArray content = parsed.getAsJsonArray("content");
        int next = 0;
        for (int i = 0; i < content.size() && next < values.size(); i++) {
            String type = str(content.get(i).getAsJsonObject(), "type");
            if (type.equals("label") || type.equals("header") || type.equals("divider")) continue;
            out.put(Integer.toString(i), values.get(next++));
        }
        return out;
    }

    /** Closing the form the way the client does: a response without data, cancel reason "user closed". */
    Answer close() {
        Answer a = form != null ? new Answer(form, null, null, -1) : null;
        closeAll();
        npc = null;
        return a;
    }

    private static int pick(String button, int count, IntFunction<String> label) {
        if (button == null) {
            if (count == 1) return 0;
            throw new AgentError("INVALID_PARAMS", "button is required: this form has " + count + " buttons", "Pass a button label or index.");
        }
        for (int i = 0; i < count; i++) if (BedrockHud.plain(label.apply(i)).equalsIgnoreCase(button)) return i;
        try {
            int i = Integer.parseInt(button);
            if (i >= 0 && i < count) return i;
        } catch (NumberFormatException notIndex) {
            // Not an index either: reported below.
        }
        throw new AgentError("BUTTON_NOT_FOUND", "No button '" + button + "' on this form", "gui_read lists screen.dialog.buttons.");
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e instanceof JsonPrimitive p ? p.getAsString() : e.toString();
    }

    private static JsonObject button(int index, String label) {
        JsonObject b = new JsonObject();
        b.addProperty("index", index);
        b.addProperty("label", BedrockHud.plain(label));
        return b;
    }
}
