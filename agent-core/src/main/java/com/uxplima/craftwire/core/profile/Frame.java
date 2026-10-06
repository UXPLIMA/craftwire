package com.uxplima.craftwire.core.profile;

/** One Java stack frame: class (binary name, dots), method, JVM descriptor ("" when unknown), line (or -1). */
public record Frame(String className, String method, String descriptor, int line) {
    private static final java.util.regex.Pattern HIDDEN = java.util.regex.Pattern.compile("\\$\\$Lambda[/.$]?(0x)?[0-9a-fA-F]*");

    /** class.method, with a lambda's generated class shown as Outer$$Lambda (the same every run). */
    public String qualified() {
        return display(className) + "." + method;
    }

    public static String display(String className) {
        return HIDDEN.matcher(className).replaceFirst(java.util.regex.Matcher.quoteReplacement("$$Lambda"));
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
