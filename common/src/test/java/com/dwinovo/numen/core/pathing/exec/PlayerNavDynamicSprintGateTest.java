package com.dwinovo.numen.core.pathing.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.NavigationCapabilities;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

class PlayerNavDynamicSprintGateTest {

    @Test
    void legacySpeedGateKeepsItsExactMeaning() {
        assertFalse(PlayerNav.legacySprintGate(0.99).getAsBoolean());
        assertTrue(PlayerNav.legacySprintGate(1.0).getAsBoolean());
        assertTrue(PlayerNav.legacySprintGate(2.0).getAsBoolean());
    }

    @Test
    void dynamicGateIsAdditionalToLegacySpeedGate() {
        assertFalse(PlayerNav.combinedSprintGate(0.99, () -> true).getAsBoolean());
        assertFalse(PlayerNav.combinedSprintGate(1.0, () -> false).getAsBoolean());
        assertTrue(PlayerNav.combinedSprintGate(1.0, () -> true).getAsBoolean());
    }

    @Test
    void sameSupplierCanChangeWithoutCreatingANewGate() {
        AtomicBoolean allowed = new AtomicBoolean(false);
        BooleanSupplier gate = PlayerNav.combinedSprintGate(1.0, allowed::get);

        assertFalse(gate.getAsBoolean());
        allowed.set(true);
        assertTrue(gate.getAsBoolean());
        allowed.set(false);
        assertFalse(gate.getAsBoolean());
    }

    @Test
    void falseGateDisablesSprintInsideGuardAndRestoresAfterward() {
        NavSettings settings = NavSettings.get();
        boolean original = settings.allowSprint;
        try {
            settings.allowSprint = true;
            PlayerNav.withSprintGate(() -> false,
                    () -> assertFalse(settings.allowSprint));
            assertTrue(settings.allowSprint);
        } finally {
            settings.allowSprint = original;
        }
    }

    @Test
    void trueGateOnlyAllowsAndDoesNotForceSprint() {
        NavSettings settings = NavSettings.get();
        boolean original = settings.allowSprint;
        try {
            settings.allowSprint = false;
            PlayerNav.withSprintGate(() -> true,
                    () -> assertFalse(settings.allowSprint));
            assertFalse(settings.allowSprint);
        } finally {
            settings.allowSprint = original;
        }
    }

    @Test
    void guardRestoresGlobalSettingWhenBodyThrows() {
        NavSettings settings = NavSettings.get();
        boolean original = settings.allowSprint;
        try {
            settings.allowSprint = true;
            assertThrows(IllegalStateException.class,
                    () -> PlayerNav.withSprintGate(() -> false, () -> {
                        assertFalse(settings.allowSprint);
                        throw new IllegalStateException("expected");
                    }));
            assertTrue(settings.allowSprint);
        } finally {
            settings.allowSprint = original;
        }
    }

    @Test
    void twoSequentialNavigationGatesRemainIsolated() {
        NavSettings settings = NavSettings.get();
        boolean original = settings.allowSprint;
        try {
            settings.allowSprint = true;
            PlayerNav.withSprintGate(() -> false,
                    () -> assertFalse(settings.allowSprint));
            PlayerNav.withSprintGate(() -> true,
                    () -> assertTrue(settings.allowSprint));
            assertTrue(settings.allowSprint);
        } finally {
            settings.allowSprint = original;
        }
    }

    @Test
    void supplierIsResampledOnEveryGuardedDrive() {
        NavSettings settings = NavSettings.get();
        boolean original = settings.allowSprint;
        AtomicBoolean allowed = new AtomicBoolean(false);
        try {
            settings.allowSprint = true;
            PlayerNav.withSprintGate(allowed::get,
                    () -> assertFalse(settings.allowSprint));
            allowed.set(true);
            PlayerNav.withSprintGate(allowed::get,
                    () -> assertTrue(settings.allowSprint));
        } finally {
            settings.allowSprint = original;
        }
    }

    @Test
    void legacyAndNewEntryPointSignaturesRemainCompileTimeCompatible() {
        FixedGoalConstructor fixedGoalConstructor = PlayerNav::new;
        MovingGoalConstructor movingGoalConstructor = PlayerNav::new;
        LegacyRevalidatingFactory legacyFactory = PlayerNav::toRevalidating;
        DynamicRevalidatingFactory dynamicFactory = PlayerNav::toRevalidating;

        assertNotNull(fixedGoalConstructor);
        assertNotNull(movingGoalConstructor);
        assertNotNull(legacyFactory);
        assertNotNull(dynamicFactory);
    }

    @Test
    void sprintGateDoesNotRelaxSafeFollowCapabilities() {
        NavSettings settings = NavSettings.get();
        boolean original = settings.allowSprint;
        try {
            settings.allowSprint = true;
            PlayerNav.withSprintGate(() -> true, () -> {
                assertFalse(NavigationCapabilities.SAFE_FOLLOW.permitsBreak(true));
                assertFalse(NavigationCapabilities.SAFE_FOLLOW.permitsPlace(true));
                assertFalse(NavigationCapabilities.SAFE_FOLLOW
                        .permitsWaterBucketLanding(true));
            });
        } finally {
            settings.allowSprint = original;
        }
    }

    @FunctionalInterface
    private interface FixedGoalConstructor {
        PlayerNav create(NumenPlayer player, BlockPos goal, double speed,
                         BooleanSupplier reached);
    }

    @FunctionalInterface
    private interface MovingGoalConstructor {
        PlayerNav create(NumenPlayer player, Supplier<BlockPos> goal, double speed,
                         BooleanSupplier reached);
    }

    @FunctionalInterface
    private interface LegacyRevalidatingFactory {
        PlayerNav create(NumenPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                         double speed, BooleanSupplier reached,
                         PlayerNav.ContextProvider contextProvider);
    }

    @FunctionalInterface
    private interface DynamicRevalidatingFactory {
        PlayerNav create(NumenPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                         double speed, BooleanSupplier reached,
                         PlayerNav.ContextProvider contextProvider,
                         BooleanSupplier sprintAllowed);
    }
}
