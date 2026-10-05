package com.uxplima.craftwire.core;

/** An expected failure reported to the model as {code, message, hint}. */
public class AgentError extends RuntimeException {
    private final String code;
    private final String hint;

    public AgentError(String code, String message, String hint) {
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
