package com.uxplima.craftwire.build;

import java.util.Arrays;

/** Positions to state ids: open addressing, about 12 bytes per entry. Values are never negative. */
public final class LongIntMap {
    public static final int NONE = -1;
    private static final long EMPTY = Long.MIN_VALUE;
    private static final float LOAD = 0.6f;

    @FunctionalInterface
    public interface Entry {
        void accept(long key, int value);
    }

    private long[] keys;
    private int[] values;
    private int size;
    private int mask;

    public LongIntMap() {
        this(16);
    }

    public LongIntMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(4, (int) (expected / LOAD)) - 1) << 1;
        alloc(cap);
    }

    private void alloc(int capacity) {
        keys = new long[capacity];
        Arrays.fill(keys, EMPTY);
        values = new int[capacity];
        mask = capacity - 1;
    }

    private static int hash(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        return (int) k;
    }

    public int get(long key) {
        for (int i = hash(key) & mask; ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == EMPTY) return NONE;
            if (k == key) return values[i];
        }
    }

    public boolean containsKey(long key) {
        return get(key) != NONE;
    }

    /** Sets the value and returns the previous one, or NONE. */
    public int put(long key, int value) {
        if (key == EMPTY) throw new IllegalArgumentException("Position out of range");
        if (value < 0) throw new IllegalArgumentException("Negative value " + value);
        if (size + 1 > keys.length * LOAD) grow();
        for (int i = hash(key) & mask; ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == EMPTY) {
                keys[i] = key;
                values[i] = value;
                size++;
                return NONE;
            }
            if (k == key) {
                int old = values[i];
                values[i] = value;
                return old;
            }
        }
    }

    public int size() {
        return size;
    }

    public void forEach(Entry e) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY) e.accept(keys[i], values[i]);
        }
    }

    public LongIntMap copy() {
        LongIntMap c = new LongIntMap(4);
        c.keys = keys.clone();
        c.values = values.clone();
        c.size = size;
        c.mask = mask;
        return c;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        alloc(keys.length * 2);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) put(oldKeys[i], oldValues[i]);
        }
    }
}
