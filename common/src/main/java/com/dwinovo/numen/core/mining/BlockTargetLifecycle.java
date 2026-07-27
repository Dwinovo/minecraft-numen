package com.dwinovo.numen.core.mining;

/** Decides when an exact block destination no longer represents its original target. */
public final class BlockTargetLifecycle {
    private BlockTargetLifecycle() {
    }

    public static boolean isLost(
        boolean exactBlockTarget,
        boolean startedAsBlock,
        boolean currentIsAir,
        boolean currentIsTemporaryScaffold,
        boolean recentlyMined
    ) {
        return exactBlockTarget && (
            (currentIsAir && (startedAsBlock || recentlyMined))
                || (recentlyMined && currentIsTemporaryScaffold)
        );
    }
}
