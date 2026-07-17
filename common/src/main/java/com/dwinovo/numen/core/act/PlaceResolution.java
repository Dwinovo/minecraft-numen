package com.dwinovo.numen.core.act;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The complete answer of one placement resolution ({@link Placement#resolveDetailed}):
 * either a line-of-sight-verified {@link #hit} to press on, or a structured diagnosis of
 * why no press can work from where the body currently stands.
 *
 * <p>The diagnosis replaces a bare {@code null}: the caller learns <em>why</em> (branchable
 * {@link Reason}), gets a human-readable {@link #message} it can surface to the model
 * verbatim, and — when the failure is positional ({@link Reason#NO_LINE_OF_SIGHT} /
 * {@link Reason#OUT_OF_REACH} with support present) — an optional {@link #suggestedStance}:
 * a standable spot from which the best support face should be visible, so the task layer
 * can walk straight to a computed answer instead of sampling blind stances.
 *
 * <p>Invariants: success carries a hit and nothing else; failure carries a reason and a
 * non-blank message (stance optional). Exactly one of {@code hit} / {@code reason} is set.
 */
public record PlaceResolution(BlockHitResult hit, Reason reason, String message, Vec3 suggestedStance) {

    /** Why no placement hit could be produced. */
    public enum Reason {
        /** No sturdy neighbour face exists and the cell holds nothing clickable — the target
         *  floats in air. No stance change can fix this; the caller must pick another cell. */
        NO_SUPPORT,
        /** A building-blocking entity (mob, armor stand, boat …) occupies the target cell —
         *  vanilla refuses every press until it moves. No ray was spent. */
        BLOCKED_BY_ENTITY,
        /** Support exists and is within reach, but no sampled ray from the eye lands on a
         *  usable face — the view is occluded from this stance. */
        NO_LINE_OF_SIGHT,
        /** Support exists but every candidate face is beyond interaction reach. */
        OUT_OF_REACH
    }

    public PlaceResolution {
        if ((hit == null) == (reason == null)) {
            throw new IllegalArgumentException("exactly one of hit / reason must be set");
        }
        if (hit != null && (message != null || suggestedStance != null)) {
            throw new IllegalArgumentException("a success carries only the hit");
        }
        if (reason != null && (message == null || message.isBlank())) {
            throw new IllegalArgumentException("a failure must carry a human-readable message");
        }
    }

    public static PlaceResolution success(BlockHitResult hit) {
        return new PlaceResolution(hit, null, null, null);
    }

    public static PlaceResolution failure(Reason reason, String message) {
        return new PlaceResolution(null, reason, message, null);
    }

    public static PlaceResolution failure(Reason reason, String message, Vec3 suggestedStance) {
        return new PlaceResolution(null, reason, message, suggestedStance);
    }

    /** True when a pressable hit was resolved. */
    public boolean ok() {
        return hit != null;
    }
}
