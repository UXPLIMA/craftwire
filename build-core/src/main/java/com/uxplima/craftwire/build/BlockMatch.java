package com.uxplima.craftwire.build;

/** "oak_stairs" matches every oak_stairs state; "oak_stairs[facing=south]" matches states with those properties. */
public final class BlockMatch {
    private final String id;
    private final String[] props;

    public BlockMatch(String spec) {
        String s = spec.strip();
        if (!s.contains(":")) s = "minecraft:" + s;
        int b = s.indexOf('[');
        id = b < 0 ? s : s.substring(0, b);
        props = b < 0 ? new String[0] : s.substring(b + 1, s.length() - 1).split(",");
    }

    public boolean test(String fullState) {
        int b = fullState.indexOf('[');
        String fid = b < 0 ? fullState : fullState.substring(0, b);
        if (!fid.equals(id)) return false;
        String rest = b < 0 ? "" : "," + fullState.substring(b + 1, fullState.length() - 1) + ",";
        for (String p : props) if (!rest.contains("," + p.strip() + ",")) return false;
        return true;
    }
}
