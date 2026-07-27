package com.dwinovo.numen.core.mining;

import com.dwinovo.numen.core.scaffold.TemporaryScaffoldLedger;
import java.util.UUID;

/** Keeps dynamically changed coordinates from being mistaken for original mining targets. */
public final class MiningTargetIdentity {
    private MiningTargetIdentity() {
    }

    public static boolean isEligible(
        UUID companionId,
        String dimensionId,
        int x,
        int y,
        int z,
        boolean requestedBlockType
    ) {
        return requestedBlockType
            && !TemporaryScaffoldLedger.contains(companionId, dimensionId, x, y, z);
    }
}
