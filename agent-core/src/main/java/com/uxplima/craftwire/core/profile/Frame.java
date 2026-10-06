package com.uxplima.craftwire.core.profile;

/** One Java stack frame: class (binary name, dots), method, JVM descriptor, line (or -1). */
public record Frame(String className, String method, String descriptor, int line) {
    public String qualified() {
        return className + "." + method;
    }

    /**
     * The simple name of the first parameter type that looks like an event ("PlayerMoveEvent" for
     * {@code (Lorg/bukkit/event/player/PlayerMoveEvent;)V}), or null.
     */
    public String eventParameter() {
        int open = descriptor.indexOf('(');
        int close = descriptor.indexOf(')');
        if (open < 0 || close < open) return null;
        String params = descriptor.substring(open + 1, close);
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == 'L') {
                int end = params.indexOf(';', i);
                if (end < 0) return null;
                String type = params.substring(i + 1, end);
                String simple = type.substring(type.lastIndexOf('/') + 1);
                simple = simple.substring(simple.lastIndexOf('$') + 1);
                if (simple.endsWith("Event")) return simple;
                i = end + 1;
            } else {
                i++;
            }
        }
        return null;
    }
}
