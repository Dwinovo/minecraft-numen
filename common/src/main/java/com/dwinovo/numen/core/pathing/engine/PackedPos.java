package com.dwinovo.numen.core.pathing.engine;

/**
 * The engine's own coordinate packing: one {@code long} per (x, y, z) block
 * position, 26/12/26 bits with sign extension. Same LAYOUT as vanilla's
 * {@code BlockPos.asLong()} because it is a good layout — but deliberately
 * <b>no equivalence contract</b> with it: every conversion in and out of the
 * engine goes through this class (the MC adapter is the only borderland), so
 * the engine never imports a Minecraft type.
 *
 * <p>Ranges: x, z ∈ [-33,554,432, 33,554,431]; y ∈ [-2048, 2047]. Far beyond
 * any reachable world position.
 */
public final class PackedPos {

    private static final int X_BITS = 26;
    private static final int Y_BITS = 12;
    private static final int Z_BITS = 26;

    private static final long X_MASK = (1L << X_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;
    private static final long Z_MASK = (1L << Z_BITS) - 1;

    private static final int Z_SHIFT = Y_BITS;                 // 12
    private static final int X_SHIFT = Y_BITS + Z_BITS;        // 38

    private PackedPos() {}

    /** Pack a block position into one long. */
    public static long pack(int x, int y, int z) {
        return ((x & X_MASK) << X_SHIFT) | ((z & Z_MASK) << Z_SHIFT) | (y & Y_MASK);
    }

    /** X component (sign-extended). */
    public static int x(long packed) {
        return (int) (packed >> X_SHIFT);                      // arithmetic shift = sign extend
    }

    /** Y component (sign-extended). */
    public static int y(long packed) {
        return (int) (packed << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    /** Z component (sign-extended). */
    public static int z(long packed) {
        return (int) (packed << (64 - X_SHIFT) >> (64 - Z_BITS));
    }

    /** Squared euclidean distance between two packed positions. */
    public static double distSq(long a, long b) {
        double dx = x(a) - x(b);
        double dy = y(a) - y(b);
        double dz = z(a) - z(b);
        return dx * dx + dy * dy + dz * dz;
    }
}
