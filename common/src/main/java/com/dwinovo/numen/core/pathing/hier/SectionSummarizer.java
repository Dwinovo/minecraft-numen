package com.dwinovo.numen.core.pathing.hier;

/**
 * Builds one section's {@link SectionSummary} from a {@link CellSampler}:
 * uniform fast paths first, else an exact scan of each face's 16×16 boundary
 * layer. Pure — Minecraft-free, unit-testable on lambda terrains.
 */
final class SectionSummarizer {

    /** Cells scanned by one exact (non-uniform) summary — the budget unit the
     *  field build caps on. */
    static final int EXACT_SCAN_CELLS = 6 * 16 * 16;

    static SectionSummary summarize(CellSampler sampler, int sx, int sy, int sz) {
        switch (sampler.uniform(sx, sy, sz)) {
            case AIR -> {
                return SectionSummary.uniformOpen();
            }
            case SOLID_BREAKABLE -> {
                return SectionSummary.uniformSoft();
            }
            case MIXED_OR_UNKNOWN -> { /* exact scan below */ }
        }
        int bx = sx << 4;
        int by = sy << 4;
        int bz = sz << 4;
        SectionSummary.Face[] faces = new SectionSummary.Face[Directions.COUNT];
        for (int dir = 0; dir < Directions.COUNT; dir++) {
            faces[dir] = scanFace(sampler, bx, by, bz, dir);
        }
        return new SectionSummary(faces, true);
    }

    /** Classify one face from its boundary layer INSIDE the section. */
    private static SectionSummary.Face scanFace(CellSampler sampler,
                                                int bx, int by, int bz, int dir) {
        boolean anyBreakable = false;
        for (int a = 0; a < 16; a++) {
            for (int b = 0; b < 16; b++) {
                int x;
                int y;
                int z;
                switch (dir) {
                    case 0 -> { x = bx + 15; y = by + a; z = bz + b; }   // +X
                    case 1 -> { x = bx;      y = by + a; z = bz + b; }   // -X
                    case 2 -> { x = bx + a;  y = by + 15; z = bz + b; }  // +Y
                    case 3 -> { x = bx + a;  y = by;      z = bz + b; }  // -Y
                    case 4 -> { x = bx + a;  y = by + b;  z = bz + 15; } // +Z
                    default -> { x = bx + a; y = by + b;  z = bz; }      // -Z
                }
                if (sampler.passable(x, y, z)) {
                    return SectionSummary.Face.OPEN;
                }
                if (!anyBreakable && sampler.breakable(x, y, z)) {
                    anyBreakable = true;
                }
            }
        }
        return anyBreakable ? SectionSummary.Face.SOFT : SectionSummary.Face.HARD;
    }

    private SectionSummarizer() {}
}
