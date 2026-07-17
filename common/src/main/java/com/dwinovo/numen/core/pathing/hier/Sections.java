package com.dwinovo.numen.core.pathing.hier;

/**
 * Section-coordinate packing for the coarse layer. Its OWN packing — deliberately
 * neither {@code BlockPos.asLong()} nor the engine's {@code PackedPos}; the three
 * long domains must never mix, and each declares itself at its definition site.
 */
final class Sections {

    private static final int BITS = 20;
    private static final long MASK = (1L << BITS) - 1;
    private static final int BIAS = 1 << (BITS - 1);

    static long pack(int sx, int sy, int sz) {
        return ((sx + (long) BIAS) & MASK)
                | (((sy + (long) BIAS) & MASK) << BITS)
                | (((sz + (long) BIAS) & MASK) << (2 * BITS));
    }

    static int x(long key) {
        return (int) (key & MASK) - BIAS;
    }

    static int y(long key) {
        return (int) ((key >>> BITS) & MASK) - BIAS;
    }

    static int z(long key) {
        return (int) ((key >>> (2 * BITS)) & MASK) - BIAS;
    }

    static long ofCell(int x, int y, int z) {
        return pack(x >> 4, y >> 4, z >> 4);
    }

    private Sections() {}
}
