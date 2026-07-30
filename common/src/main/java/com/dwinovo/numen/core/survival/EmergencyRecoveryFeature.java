package com.dwinovo.numen.core.survival;

import com.dwinovo.numen.task.BrainChains;

public final class EmergencyRecoveryFeature {
    private static boolean registered;

    private EmergencyRecoveryFeature() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        BrainChains.register(35, EmergencyItemChain::new);
        registered = true;
    }
}
