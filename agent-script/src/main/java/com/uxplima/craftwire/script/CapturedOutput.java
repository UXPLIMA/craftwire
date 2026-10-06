package com.uxplima.craftwire.script;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** print()/console output of one eval, capped so a chatty loop cannot exhaust memory. */
final class CapturedOutput extends OutputStream {
    static final int LIMIT = 65_536;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean truncated;

    @Override
    public synchronized void write(int b) {
        if (buffer.size() < LIMIT) buffer.write(b);
        else truncated = true;
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        int room = LIMIT - buffer.size();
        if (len > room) truncated = true;
        buffer.write(b, off, Math.max(0, Math.min(len, room)));
    }

    /** Returns what was written since the last call and clears it. */
    synchronized String take() {
        String s = buffer.toString(StandardCharsets.UTF_8) + (truncated ? "\n(output truncated)" : "");
        buffer.reset();
        truncated = false;
        return s;
    }
}
