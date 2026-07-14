package com.dwinovo.numen.core.pathing.engine;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T1 — round-trip (including negatives at world-scale extremes) and distSq exactness. */
class PackedPosTest {

    private static void assertRoundTrip(int x, int y, int z) {
        long packed = PackedPos.pack(x, y, z);
        assertEquals(x, PackedPos.x(packed), "x round-trip for " + x + "," + y + "," + z);
        assertEquals(y, PackedPos.y(packed), "y round-trip for " + x + "," + y + "," + z);
        assertEquals(z, PackedPos.z(packed), "z round-trip for " + x + "," + y + "," + z);
    }

    @Test
    void roundTripOrigin() {
        assertRoundTrip(0, 0, 0);
    }

    @Test
    void roundTripWorldScaleExtremes() {
        int[] xs = {-30_000_000, -1, 0, 1, 30_000_000};
        int[] ys = {-2048, -64, 0, 63, 2047};
        int[] zs = {-30_000_000, -1, 0, 1, 30_000_000};
        for (int x : xs) {
            for (int y : ys) {
                for (int z : zs) {
                    assertRoundTrip(x, y, z);
                }
            }
        }
    }

    @Test
    void roundTripRandomInRange() {
        Random random = new Random(20260714L);
        for (int i = 0; i < 10_000; i++) {
            int x = random.nextInt(60_000_001) - 30_000_000;
            int y = random.nextInt(4096) - 2048; // full y range −2048..2047
            int z = random.nextInt(60_000_001) - 30_000_000;
            assertRoundTrip(x, y, z);
        }
    }

    @Test
    void distSqExact() {
        assertEquals(0.0, PackedPos.distSq(PackedPos.pack(5, 6, 7), PackedPos.pack(5, 6, 7)));
        // 3-4-0 triangle
        assertEquals(25.0, PackedPos.distSq(PackedPos.pack(0, 0, 0), PackedPos.pack(3, 0, 4)));
        // per-axis, with negatives across zero
        assertEquals(49.0, PackedPos.distSq(PackedPos.pack(-3, 0, 0), PackedPos.pack(4, 0, 0)));
        assertEquals(16.0, PackedPos.distSq(PackedPos.pack(0, -2, 0), PackedPos.pack(0, 2, 0)));
        assertEquals(9.0, PackedPos.distSq(PackedPos.pack(0, 0, -5), PackedPos.pack(0, 0, -2)));
        // mixed
        assertEquals(1.0 + 4.0 + 9.0,
                PackedPos.distSq(PackedPos.pack(10, -10, 100), PackedPos.pack(11, -12, 97)));
        // symmetric
        long a = PackedPos.pack(-1_000_000, 2000, 1_000_000);
        long b = PackedPos.pack(-999_990, -2000, 999_970);
        assertEquals(PackedPos.distSq(a, b), PackedPos.distSq(b, a));
        assertEquals(10.0 * 10 + 4000.0 * 4000 + 30.0 * 30, PackedPos.distSq(a, b));
    }
}
