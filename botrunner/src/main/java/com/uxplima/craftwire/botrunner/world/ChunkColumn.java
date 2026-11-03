package com.uxplima.craftwire.botrunner.world;

/**
 * One chunk column's block states, one 16x16x16 section after another from the dimension's lowest y. A section that
 * is all air is null, so the empty sky of a column costs nothing. State ids are the kind's own dense ids (Java's
 * block state registry ids, Bedrock's palette indices), 0 being air. Runner loop thread.
 */
public final class ChunkColumn {
    private static final int SECTION = 16 * 16 * 16;

    private final int minY;
    private final char[][] sections;

    public ChunkColumn(int minY, int sectionCount) {
        this.minY = minY;
        this.sections = new char[sectionCount][];
    }

    public int minY() {
        return minY;
    }

    public int sectionCount() {
        return sections.length;
    }

    /** The state at block coordinates inside this column (x and z are taken modulo 16); air above and below it. */
    public int get(int x, int y, int z) {
        int s = (y - minY) >> 4;
        if (s < 0 || s >= sections.length || sections[s] == null) return 0;
        return sections[s][index(x, y, z)];
    }

    public void set(int x, int y, int z, int state) {
        int s = (y - minY) >> 4;
        if (s < 0 || s >= sections.length) return;
        if (sections[s] == null) {
            if (state == 0) return;
            sections[s] = new char[SECTION];
        }
        sections[s][index(x, y, z)] = (char) state;
    }

    /** Replaces a whole section (from a decoded chunk packet); null or all air clears it. */
    public void section(int index, char[] states) {
        if (index < 0 || index >= sections.length) return;
        sections[index] = states == null || allAir(states) ? null : states;
    }

    private static boolean allAir(char[] states) {
        for (char c : states) if (c != 0) return false;
        return true;
    }

    /** YZX order, as both protocols store section blocks. */
    private static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    /** Bytes held, for the runner's stats. */
    public long bytes() {
        long n = 0;
        for (char[] s : sections) if (s != null) n += SECTION * 2L;
        return n;
    }
}
