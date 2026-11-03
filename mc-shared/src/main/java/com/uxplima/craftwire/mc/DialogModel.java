package com.uxplima.craftwire.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.ButtonListDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogListDialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.SimpleDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;

/**
 * A server dialog (ClientboundShowDialogPacket) as an agent reads and answers it: its title, body, inputs and
 * buttons, and the click event a vanilla client would run for a chosen button with the given input values
 * (CommonDialogScreen and the input widgets' value getters).
 */
public final class DialogModel {
    /** One button: its label and what clicking it does (empty = just closes). */
    public record Button(String label, Optional<Action> action, Holder<Dialog> opens) {}

    private DialogModel() {}

    public static List<Button> buttons(Dialog d) {
        List<Button> out = new ArrayList<>();
        switch (d) {
            case SimpleDialog s -> s.mainActions().forEach(b -> out.add(button(b)));
            case MultiActionDialog m -> m.actions().forEach(b -> out.add(button(b)));
            case DialogListDialog l -> l.dialogs().stream()
                    .forEach(h -> out.add(new Button(h.value().common().computeExternalTitle().getString(), Optional.empty(), h)));
            default -> { }
        }
        if (d instanceof ButtonListDialog l) l.exitAction().ifPresent(b -> out.add(button(b)));
        return out;
    }

    private static Button button(ActionButton b) {
        return new Button(b.button().label().getString(), b.action(), null);
    }

    public static JsonObject json(Dialog d) {
        JsonObject o = new JsonObject();
        o.addProperty("title", d.common().title().getString());
        JsonArray body = new JsonArray();
        for (DialogBody b : d.common().body()) if (b instanceof PlainMessage m) body.add(m.contents().getString());
        o.add("body", body);
        JsonArray inputs = new JsonArray();
        for (Input in : d.common().inputs()) inputs.add(input(in));
        o.add("inputs", inputs);
        JsonArray buttons = new JsonArray();
        List<Button> list = buttons(d);
        for (int i = 0; i < list.size(); i++) {
            JsonObject b = new JsonObject();
            b.addProperty("index", i);
            b.addProperty("label", list.get(i).label());
            buttons.add(b);
        }
        o.add("buttons", buttons);
        o.addProperty("canClose", d.common().canCloseWithEscape());
        return o;
    }

    private static JsonObject input(Input in) {
        JsonObject o = new JsonObject();
        o.addProperty("key", in.key());
        switch (in.control()) {
            case TextInput t -> {
                o.addProperty("type", "text");
                o.addProperty("label", t.label().getString());
                o.addProperty("initial", t.initial());
                o.addProperty("maxLength", t.maxLength());
            }
            case BooleanInput b -> {
                o.addProperty("type", "boolean");
                o.addProperty("label", b.label().getString());
                o.addProperty("initial", b.initial());
            }
            case NumberRangeInput n -> {
                o.addProperty("type", "number");
                o.addProperty("label", n.label().getString());
                o.addProperty("start", n.rangeInfo().start());
                o.addProperty("end", n.rangeInfo().end());
            }
            case SingleOptionInput s -> {
                o.addProperty("type", "option");
                o.addProperty("label", s.label().getString());
                JsonArray opts = new JsonArray();
                s.entries().forEach(e -> opts.add(e.id()));
                o.add("options", opts);
            }
            default -> o.addProperty("type", "unknown");
        }
        return o;
    }

    /** The button named by its label (case-insensitive) or index. */
    public static Button pick(Dialog d, String which) {
        List<Button> list = buttons(d);
        if (list.isEmpty()) throw new AgentError("NO_BUTTONS", "This dialog has no buttons", "Close it with gui_close.");
        if (which == null) return list.getFirst();
        for (Button b : list) if (b.label().equalsIgnoreCase(which)) return b;
        try {
            int i = Integer.parseInt(which);
            if (i >= 0 && i < list.size()) return list.get(i);
        } catch (NumberFormatException ignored) {
            // not an index: report the labels below
        }
        throw new AgentError("BUTTON_NOT_FOUND", "No button " + which + " in the dialog",
                "Buttons: " + list.stream().map(Button::label).toList() + " (by label or index).");
    }

    /** The click event the button runs with these input values (missing inputs keep their initial value). */
    public static Optional<ClickEvent> clickEvent(Dialog d, Button b, Map<String, String> values) {
        if (b.action().isEmpty()) return Optional.empty();
        Map<String, Action.ValueGetter> getters = new HashMap<>();
        for (Input in : d.common().inputs()) getters.put(in.key(), getter(in, values.get(in.key())));
        return b.action().get().createAction(getters);
    }

    private static Action.ValueGetter getter(Input in, String given) {
        return switch (in.control()) {
            case TextInput t -> Action.ValueGetter.of(given != null ? given : t.initial());
            case BooleanInput bi -> {
                boolean v = given != null ? Boolean.parseBoolean(given.toLowerCase(Locale.ROOT)) : bi.initial();
                yield new Action.ValueGetter() {
                    @Override
                    public String asTemplateSubstitution() {
                        return v ? bi.onTrue() : bi.onFalse();
                    }

                    @Override
                    public Tag asTag() {
                        return ByteTag.valueOf(v);
                    }
                };
            }
            case NumberRangeInput n -> {
                float v = given != null ? Float.parseFloat(given) : n.rangeInfo().computeScaledValue(n.rangeInfo().initialSliderValue());
                yield new Action.ValueGetter() {
                    @Override
                    public String asTemplateSubstitution() {
                        return v == (int) v ? Integer.toString((int) v) : Float.toString(v);
                    }

                    @Override
                    public Tag asTag() {
                        return FloatTag.valueOf(v);
                    }
                };
            }
            case SingleOptionInput s -> {
                String id = given != null ? given : s.initial().map(SingleOptionInput.Entry::id).orElse("");
                yield new Action.ValueGetter() {
                    @Override
                    public String asTemplateSubstitution() {
                        return id;
                    }

                    @Override
                    public Tag asTag() {
                        return StringTag.valueOf(id);
                    }
                };
            }
            default -> Action.ValueGetter.of(given == null ? "" : given);
        };
    }

    /** A label for logs: the dialog's title. */
    public static String title(Holder<Dialog> h) {
        Component t = h.value().common().title();
        return t.getString();
    }
}
