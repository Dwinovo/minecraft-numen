package com.dwinovo.numen.core.pathing.hier;

/** The six section-face directions, index-aligned with {@link SectionSummary}:
 *  +X, -X, +Y, -Y, +Z, -Z. Pure (no Minecraft {@code Direction}). */
final class Directions {

    static final int COUNT = 6;
    static final int[] DX = {1, -1, 0, 0, 0, 0};
    static final int[] DY = {0, 0, 1, -1, 0, 0};
    static final int[] DZ = {0, 0, 0, 0, 1, -1};

    static int opposite(int dir) {
        return dir ^ 1;   // pairs are adjacent: (+X,-X)=(0,1), (+Y,-Y)=(2,3), (+Z,-Z)=(4,5)
    }

    private Directions() {}
}
