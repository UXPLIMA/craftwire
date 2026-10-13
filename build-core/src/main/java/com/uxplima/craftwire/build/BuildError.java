package com.uxplima.craftwire.build;

/** A build failure the caller can act on: code (SCRIPT_ERROR, BAD_BLOCK, TOO_LARGE, ...), message and a hint. */
public final class BuildError extends RuntimeException {
    private final String code;
    private final String hint;

    public BuildError(String code, String message, String hint) {
        super(message);
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
