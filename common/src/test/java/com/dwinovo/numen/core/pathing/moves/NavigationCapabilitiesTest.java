package com.dwinovo.numen.core.pathing.moves;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationCapabilitiesTest {

    @FunctionalInterface
    private interface LegacySimpleConstructor {
        CalculationContext create(ServerPlayer player, BlockGetter view,
                                  ChunkLoadedTest loadedTest, boolean threaded);
    }

    @FunctionalInterface
    private interface LegacyProtectedConstructor {
        CalculationContext create(ServerPlayer player, BlockGetter view,
                                  ChunkLoadedTest loadedTest, boolean threaded,
                                  LongSet sacred, LongSet deniedPlace);
    }

    @FunctionalInterface
    private interface CapabilityConstructor {
        CalculationContext create(ServerPlayer player, BlockGetter view,
                                  ChunkLoadedTest loadedTest, boolean threaded,
                                  LongSet sacred, LongSet deniedPlace,
                                  NavigationCapabilities capabilities);
    }

    @Test
    void presetsExposeExpectedHardLimits() {
        assertTrue(NavigationCapabilities.DEFAULT.allowBreak());
        assertTrue(NavigationCapabilities.DEFAULT.allowPlace());
        assertTrue(NavigationCapabilities.DEFAULT.allowWaterBucketLanding());

        assertFalse(NavigationCapabilities.SAFE_FOLLOW.allowBreak());
        assertFalse(NavigationCapabilities.SAFE_FOLLOW.allowPlace());
        assertFalse(NavigationCapabilities.SAFE_FOLLOW.allowWaterBucketLanding());
    }

    @Test
    void safeFollowCannotBeRelaxedByGlobalSettings() {
        NavigationCapabilities safe = NavigationCapabilities.SAFE_FOLLOW;

        assertFalse(safe.permitsBreak(true));
        assertFalse(safe.permitsPlace(true));
        assertFalse(safe.permitsWaterBucketLanding(true));
        assertEquals(List.of(), safe.permittedBreakExceptions(List.of("global exception")));
    }

    @Test
    void defaultPreservesConfiguredBehaviorAndSnapshotsExceptions() {
        NavigationCapabilities defaults = NavigationCapabilities.DEFAULT;
        ArrayList<String> configured = new ArrayList<>(List.of("stone"));
        List<String> snapshot = defaults.permittedBreakExceptions(configured);
        configured.add("dirt");

        assertTrue(defaults.permitsBreak(true));
        assertTrue(defaults.permitsPlace(true));
        assertTrue(defaults.permitsWaterBucketLanding(true));
        assertFalse(defaults.permitsBreak(false));
        assertFalse(defaults.permitsPlace(false));
        assertFalse(defaults.permitsWaterBucketLanding(false));
        assertEquals(List.of("stone"), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("sand"));
    }

    @Test
    void immutableValuesDoNotLeakAcrossNavigations() {
        NavigationCapabilities safe =
                new NavigationCapabilities(false, false, false);
        NavigationCapabilities defaults =
                new NavigationCapabilities(true, true, true);

        assertEquals(NavigationCapabilities.SAFE_FOLLOW, safe);
        assertEquals(NavigationCapabilities.DEFAULT, defaults);
        assertNotSame(safe, defaults);
        assertTrue(defaults.allowBreak());
        assertFalse(safe.allowBreak());
    }

    @Test
    void legacyAndExplicitCalculationContextEntrypointsStillCompile() {
        LegacySimpleConstructor simple = CalculationContext::new;
        LegacyProtectedConstructor protectedCells = CalculationContext::new;
        CapabilityConstructor explicit = CalculationContext::new;

        assertTrue(simple != null && protectedCells != null && explicit != null);
    }
}
