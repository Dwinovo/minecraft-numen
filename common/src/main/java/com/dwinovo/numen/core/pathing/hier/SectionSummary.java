package com.dwinovo.numen.core.pathing.hier;

/**
 * One 16³ section's connectivity summary: a three-state classification per
 * face, computed from the boundary layer of cells INSIDE this section.
 *
 * <ul>
 *   <li>{@link Face#OPEN} — the face layer holds at least one passable cell:
 *       a body might cross here as-is;</li>
 *   <li>{@link Face#SOFT} — nothing passable, but at least one cell could be
 *       dug open;</li>
 *   <li>{@link Face#HARD} — the whole face layer is undiggable (bedrock /
 *       protected / hazard): no crossing, ever.</li>
 * </ul>
 *
 * {@link #exact} records whether the faces come from an exact full scan
 * (uniform fast paths count as exact — they are definitionally right when the
 * probe is right, and a wrong probe only mis-states in the sound direction,
 * see {@link CellSampler.Uniform}).
 */
public final class SectionSummary {

    public enum Face { OPEN, SOFT, HARD }

    /** Face order: +X, -X, +Y, -Y, +Z, -Z (see {@link Directions}). */
    private final Face[] faces;
    public final boolean exact;

    SectionSummary(Face[] faces, boolean exact) {
        this.faces = faces;
        this.exact = exact;
    }

    public Face face(int dir) {
        return faces[dir];
    }

    static SectionSummary uniformOpen() {
        return new SectionSummary(new Face[]{Face.OPEN, Face.OPEN, Face.OPEN,
                Face.OPEN, Face.OPEN, Face.OPEN}, true);
    }

    static SectionSummary uniformSoft() {
        return new SectionSummary(new Face[]{Face.SOFT, Face.SOFT, Face.SOFT,
                Face.SOFT, Face.SOFT, Face.SOFT}, true);
    }
}
