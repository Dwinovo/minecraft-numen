package com.dwinovo.numen.core.pathing.hier;

/**
 * The coarse layer's only window onto the world — pure and Minecraft-free, so
 * summaries and fields are unit-testable against lambda terrains. Coordinates
 * are GLOBAL block coordinates.
 *
 * <p>Semantics contract (what the summarizer builds on):
 * <ul>
 *   <li>{@link #passable} — may a body occupy/walk through this cell as-is;</li>
 *   <li>{@link #breakable} — could this cell be opened by digging at all
 *       (regardless of current tools): not bedrock, not a hazard, not a
 *       protected block. Tool-capability refinement is deliberately NOT here —
 *       the coarse layer summarizes TERRAIN; capability is a query-time
 *       concern (v2).</li>
 *   <li>{@link #uniform} — an optional fast path: a section known to be all
 *       air (or all diggable solid) skips the face scan. A wrong AIR answer
 *       only over-connects (sound for the sealed verdict, slightly optimistic
 *       for the field); a wrong SOLID answer only downgrades OPEN faces to
 *       SOFT (bounded pessimism). {@link Uniform#MIXED_OR_UNKNOWN} always
 *       triggers the exact scan, and HARD faces only ever come from exact
 *       scans — the sealed verdict's soundness rests on that.</li>
 * </ul>
 */
public interface CellSampler {

    boolean passable(int x, int y, int z);

    boolean breakable(int x, int y, int z);

    enum Uniform { AIR, SOLID_BREAKABLE, MIXED_OR_UNKNOWN }

    /** Section-level uniformity probe; {@code sx/sy/sz} are section coords. */
    default Uniform uniform(int sx, int sy, int sz) {
        return Uniform.MIXED_OR_UNKNOWN;
    }
}
