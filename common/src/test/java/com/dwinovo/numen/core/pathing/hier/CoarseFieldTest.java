package com.dwinovo.numen.core.pathing.hier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure coarse-layer pins on lambda terrains (no Minecraft). Section coords are
 * small; cells are global block coords (section = coord >> 4). The load-bearing
 * assertions: the field flows along walkable corridors before digging through
 * rock (the SOFT penalty), a truncated build never claims sealed, and sealed
 * only comes from a naturally exhausted sweep.
 */
class CoarseFieldTest {

    private static final double WALK = 16.0;
    private static final double SOFT = 40.0;

    /** A world of solid diggable rock with a set of AIR sections carved out. */
    private static CellSampler rockWithAir(java.util.Set<Long> airSections) {
        return new CellSampler() {
            private boolean air(int x, int y, int z) {
                return airSections.contains(Sections.ofCell(x, y, z));
            }
            @Override public boolean passable(int x, int y, int z) { return air(x, y, z); }
            @Override public boolean breakable(int x, int y, int z) { return !air(x, y, z); }
            @Override public Uniform uniform(int sx, int sy, int sz) {
                return airSections.contains(Sections.pack(sx, sy, sz))
                        ? Uniform.AIR : Uniform.SOLID_BREAKABLE;
            }
        };
    }

    private static java.util.Set<Long> sections(int[][] coords) {
        java.util.Set<Long> s = new java.util.HashSet<>();
        for (int[] c : coords) {
            s.add(Sections.pack(c[0], c[1], c[2]));
        }
        return s;
    }

    @Test
    void fieldPrefersTheWalkableCorridorOverDiggingStraight() {
        // Goal at section (0,0,0); start at (4,0,0). A walkable L-corridor
        // (0,0,0)→(0,0,1)→…→(4,0,1)→(4,0,0) exists; the straight line is rock.
        java.util.Set<Long> air = sections(new int[][]{
                {0, 0, 0}, {0, 0, 1}, {1, 0, 1}, {2, 0, 1}, {3, 0, 1}, {4, 0, 1}, {4, 0, 0}});
        CoarseField f = CoarseField.build(rockWithAir(air), null,
                4 * 16 + 8, 8, 8,      // start cell in section (4,0,0)
                8, 8, 8,               // goal cell in section (0,0,0)
                WALK, SOFT, 4096, 512);
        // Corridor distance: 6 open crossings = 96. Straight dig: 4 soft
        // crossings = 4×56 = 224. The field must price the start via the corridor.
        assertEquals(6 * WALK, f.boundAt(4 * 16 + 8, 8, 8), 1e-9,
                "start section must be priced along the walkable corridor");
        // A section deeper in the rock pays the soft penalty.
        assertTrue(f.boundAt(2 * 16 + 8, 8, 8) > 2 * WALK,
                "rock interior must carry the dig penalty");
    }

    @Test
    void unknownTerrainReadsZeroNeverALie() {
        java.util.Set<Long> air = sections(new int[][]{{0, 0, 0}});
        CoarseField f = CoarseField.build(rockWithAir(air), null,
                8, 8, 8, 8, 8, 8, WALK, SOFT, 16, 512);
        assertEquals(0.0, f.boundAt(100 * 16, 8, 8), 1e-9,
                "sections the sweep never priced bound at 0 — no information, no lie");
    }

    @Test
    void truncatedBuildNeverClaimsSealed() {
        // Start far away with a tiny section cap: the sweep truncates first.
        java.util.Set<Long> air = sections(new int[][]{{0, 0, 0}});
        CoarseField f = CoarseField.build(rockWithAir(air), null,
                40 * 16, 8, 8, 8, 8, 8, WALK, SOFT, 4, 512);
        assertFalse(f.sealed(), "a capped sweep must stay agnostic");
    }

    @Test
    void sealedFiresOnlyOnNaturalExhaustionThroughHardWalls() {
        // Goal chamber walled by UNDIGGABLE rock (breakable=false everywhere
        // solid): the sweep exhausts inside the chamber without reaching start.
        java.util.Set<Long> air = sections(new int[][]{{0, 0, 0}});
        CellSampler bedrockWorld = new CellSampler() {
            private boolean air(int x, int y, int z) {
                return air0(x, y, z);
            }
            private boolean air0(int x, int y, int z) {
                return air.contains(Sections.ofCell(x, y, z));
            }
            @Override public boolean passable(int x, int y, int z) { return air(x, y, z); }
            @Override public boolean breakable(int x, int y, int z) { return false; }
            // No uniform fast path: HARD faces must come from exact scans.
        };
        CoarseField f = CoarseField.build(bedrockWorld, null,
                10 * 16, 8, 8, 8, 8, 8, WALK, SOFT, 4096, 512);
        assertTrue(f.sealed(), "hard-walled chamber with exhausted sweep is sealed");
    }

    @Test
    void softWallsAreNeverSealed() {
        // Same chamber but the rock is diggable — reachable by digging, so the
        // sweep keeps expanding and (bounded by cap) never proves sealed.
        java.util.Set<Long> air = sections(new int[][]{{0, 0, 0}});
        CoarseField f = CoarseField.build(rockWithAir(air), null,
                10 * 16, 8, 8, 8, 8, 8, WALK, SOFT, 512, 4096);
        assertFalse(f.sealed(), "diggable rock is not a seal");
    }

    @Test
    void summaryCacheServesWithinTtlAndExpires() {
        SummaryCache cache = new SummaryCache(20);
        cache.tick(100);
        SectionSummary s = SectionSummary.uniformOpen();
        cache.put(Sections.pack(1, 2, 3), s);
        assertEquals(s, cache.get(Sections.pack(1, 2, 3)));
        cache.tick(119);
        assertEquals(s, cache.get(Sections.pack(1, 2, 3)), "within TTL");
        cache.tick(121);
        assertEquals(null, cache.get(Sections.pack(1, 2, 3)), "expired");
    }

    @Test
    void sectionPackingRoundTrips() {
        long k = Sections.pack(-321, 17, 4096);
        assertEquals(-321, Sections.x(k));
        assertEquals(17, Sections.y(k));
        assertEquals(4096, Sections.z(k));
        assertEquals(Sections.pack(-3, -1, 5), Sections.ofCell(-33, -16, 80),
                "negative cell coords floor-divide into sections");
    }
}
