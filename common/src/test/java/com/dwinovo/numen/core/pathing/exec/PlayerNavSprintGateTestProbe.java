package com.dwinovo.numen.core.pathing.exec;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import com.dwinovo.numen.core.pathing.settings.NavSettings;

/**
 * Test-only bridge proving that a gate captured by another package reaches the
 * exact guard used by {@link PlayerNav}.
 */
public final class PlayerNavSprintGateTestProbe {

    private PlayerNavSprintGateTestProbe() {}

    public static boolean observeGuard(BooleanSupplier gate) {
        NavSettings settings = NavSettings.get();
        boolean original = settings.allowSprint;
        AtomicBoolean observed = new AtomicBoolean();
        try {
            settings.allowSprint = true;
            PlayerNav.withSprintGate(gate,
                    () -> observed.set(settings.allowSprint));
            return observed.get();
        } finally {
            settings.allowSprint = original;
        }
    }
}
