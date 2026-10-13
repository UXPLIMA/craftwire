package com.uxplima.craftwire.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Unknown block strings seen during a run, each with its closest ids; reported together after the run. */
public final class BadBlocks {
    private final Map<String, List<String>> bad = new LinkedHashMap<>();

    public void add(String spec, List<String> suggestions) {
        bad.putIfAbsent(spec, suggestions);
    }

    public int size() {
        return bad.size();
    }

    public String message() {
        StringBuilder b = new StringBuilder("Unknown blocks: ");
        bad.forEach((spec, s) -> b.append("'").append(spec).append("'").append(s.isEmpty() ? "" : " (did you mean " + String.join(", ", s) + "?)").append("; "));
        return b.substring(0, b.length() - 2);
    }
}
