package com.uxplima.craftwire.core;

import com.google.gson.JsonObject;

/**
 * A JavaScript session for server_eval and client_eval (GraalJS, in agent-script). Agents that load GraalJS in a
 * class loader of their own talk to it through this interface.
 */
public interface ScriptRunner extends AutoCloseable {
    /** Runs `code` on the calling thread and returns {result, output}; AgentError TIMEOUT or EVAL_ERROR on failure. */
    JsonObject eval(String code, long timeoutMs);

    /** Starts the next eval with fresh globals, without waiting for a running one. */
    void requestReset();

    /** Drops the globals now. */
    void resetSession();

    @Override
    void close();
}
