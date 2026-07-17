package com.dwinovo.numen.core.pathing.calc;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.Container;
import net.minecraft.world.level.BlockGetter;

/** Test-tree bridge to the package-private {@link NavContext#forView} seam, for
 *  tests that live outside the {@code calc} package (e.g. movement-layer pins).
 *  Production code must use {@code forSearch}/{@code forExecution}. */
public final class TestNavContexts {

    private TestNavContexts() {}

    public static NavContext forView(BlockGetter view, Container inventory,
                                     boolean forceBreak, LongSet sacred) {
        return NavContext.forView(view, inventory, forceBreak, sacred);
    }
}
