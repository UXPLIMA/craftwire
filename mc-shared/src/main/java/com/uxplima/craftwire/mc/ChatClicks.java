package com.uxplima.craftwire.mc;

import com.uxplima.craftwire.bot.inbox.ChatAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * The clickable parts of a chat component as a player sees them: runs of text that share one click event, with the
 * hover text of the run. Shared by the plugin bot, the Java protocol bot and the Fabric client.
 */
public final class ChatClicks {
    /** One clickable run: its text, the click event itself (with payloads and inline dialogs) and its hover text. */
    public record Part(String text, ClickEvent event, String hover) {
        public ChatAction action() {
            return ChatClicks.action(text, event, hover);
        }
    }

    private ChatClicks() {}

    public static List<Part> parts(Component c) {
        List<Part> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        ClickEvent[] current = {null};
        String[] hover = {null};
        c.visit((Style style, String part) -> {
            ClickEvent e = style.getClickEvent();
            if (e != current[0] && current[0] != null) {
                out.add(new Part(text.toString(), current[0], hover[0]));
                text.setLength(0);
            }
            current[0] = e;
            if (e != null) {
                text.append(part);
                hover[0] = hoverText(style.getHoverEvent());
            }
            return Optional.empty();
        }, Style.EMPTY);
        if (current[0] != null) out.add(new Part(text.toString(), current[0], hover[0]));
        return out;
    }

    public static List<ChatAction> of(Component c) {
        return parts(c).stream().map(Part::action).toList();
    }

    /** The hover text of the first part that has one, or null. */
    public static String hover(Component c) {
        return c.visit((Style style, String part) -> Optional.ofNullable(hoverText(style.getHoverEvent())), Style.EMPTY).orElse(null);
    }

    private static String hoverText(HoverEvent h) {
        return h instanceof HoverEvent.ShowText t ? t.value().getString() : null;
    }

    static ChatAction action(String text, ClickEvent e, String hover) {
        return switch (e) {
            case ClickEvent.RunCommand r -> new ChatAction(text, "run_command", r.command(), hover);
            case ClickEvent.SuggestCommand s -> new ChatAction(text, "suggest_command", s.command(), hover);
            case ClickEvent.OpenUrl u -> new ChatAction(text, "open_url", u.uri().toString(), hover);
            case ClickEvent.CopyToClipboard cp -> new ChatAction(text, "copy_to_clipboard", cp.value(), hover);
            case ClickEvent.ChangePage p -> new ChatAction(text, "change_page", Integer.toString(p.page()), hover);
            case ClickEvent.ShowDialog d -> new ChatAction(text, "show_dialog", d.dialog().getRegisteredName(), hover);
            case ClickEvent.Custom cu -> new ChatAction(text, "custom", cu.id().toString(), hover);
            default -> new ChatAction(text, e.action().getSerializedName(), "", hover);
        };
    }
}
