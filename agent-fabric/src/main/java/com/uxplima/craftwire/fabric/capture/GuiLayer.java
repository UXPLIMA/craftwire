package com.uxplima.craftwire.fabric.capture;

/** What a transparent capture shows. "world" (today's capture) is not a GuiLayer. */
public enum GuiLayer {
    GUI, TOOLTIP, GUI_TOOLTIP;

    /** "gui", "tooltip", "gui+tooltip"; null for "world" or when absent. */
    public static GuiLayer parse(String s) {
        if (s == null || s.equals("world")) return null;
        return switch (s) {
            case "gui" -> GUI;
            case "tooltip" -> TOOLTIP;
            case "gui+tooltip" -> GUI_TOOLTIP;
            default -> throw new IllegalArgumentException("layer must be world, gui, tooltip or gui+tooltip");
        };
    }

    public boolean screen() { return this != TOOLTIP; }

    public boolean tooltip() { return this != GUI; }
}
