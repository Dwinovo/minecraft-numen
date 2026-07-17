package com.dwinovo.numen.core.act;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Semantics of the placement resolver's structured answer — headless (a
 * {@link BlockHitResult} is plain data, no bootstrapped game needed): a success
 * carries exactly a hit, a failure carries exactly a reason + message, and the
 * invariants reject every malformed mix.
 */
class PlaceResolutionTest {

    private static BlockHitResult someHit() {
        return new BlockHitResult(new Vec3(0.5, 1.0, 0.5), Direction.UP, new BlockPos(0, 0, 0), false);
    }

    @Test
    void successCarriesOnlyTheHit() {
        BlockHitResult hit = someHit();
        PlaceResolution r = PlaceResolution.success(hit);
        assertTrue(r.ok());
        assertSame(hit, r.hit());
        assertNull(r.reason());
        assertNull(r.message());
        assertNull(r.suggestedStance());
    }

    @Test
    void failureCarriesReasonMessageAndOptionalStance() {
        PlaceResolution bare = PlaceResolution.failure(
                PlaceResolution.Reason.NO_SUPPORT, "nothing to place against");
        assertFalse(bare.ok());
        assertNull(bare.hit());
        assertEquals(PlaceResolution.Reason.NO_SUPPORT, bare.reason());
        assertEquals("nothing to place against", bare.message());
        assertNull(bare.suggestedStance());

        Vec3 stance = new Vec3(2.5, 64, 0.5);
        PlaceResolution positioned = PlaceResolution.failure(
                PlaceResolution.Reason.NO_LINE_OF_SIGHT, "view blocked", stance);
        assertFalse(positioned.ok());
        assertEquals(stance, positioned.suggestedStance());
    }

    @Test
    void rejectsHitAndReasonTogether() {
        assertThrows(IllegalArgumentException.class, () -> new PlaceResolution(
                someHit(), PlaceResolution.Reason.NO_SUPPORT, "contradiction", null));
    }

    @Test
    void rejectsNeitherHitNorReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaceResolution(null, null, null, null));
    }

    @Test
    void rejectsAFailureWithoutAMessage() {
        assertThrows(IllegalArgumentException.class, () -> new PlaceResolution(
                null, PlaceResolution.Reason.OUT_OF_REACH, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PlaceResolution(
                null, PlaceResolution.Reason.OUT_OF_REACH, "   ", null));
    }

    @Test
    void rejectsASuccessDressedWithDiagnosisFields() {
        assertThrows(IllegalArgumentException.class, () -> new PlaceResolution(
                someHit(), null, "should not be here", null));
        assertThrows(IllegalArgumentException.class, () -> new PlaceResolution(
                someHit(), null, null, new Vec3(0, 0, 0)));
    }
}
