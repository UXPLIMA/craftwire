package com.uxplima.craftwire.api;

/**
 * An expected failure of a tool, shown to the AI as a Craftwire error: a code it can branch on, a message, and a hint
 * with the next step.
 */
public class ToolException extends RuntimeException {
    private final String code;
    private final String hint;

    /**
     * @param code UPPER_SNAKE_CASE, e.g. {@code PLAYER_OFFLINE}
     * @param message what went wrong
     * @param hint what to do about it, or null
     */
    public ToolException(String code, String message, String hint) {
        super(message, null, false, false);
        this.code = code;
        this.hint = hint;
    }

    public String code() {
        return code;
    }

    public String hint() {
        return hint;
    }
}
