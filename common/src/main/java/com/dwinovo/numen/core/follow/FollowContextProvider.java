package com.dwinovo.numen.core.follow;

import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.NavigationCapabilities;
import com.dwinovo.numen.entity.NumenPlayer;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * 自动跟随专用的无状态导航上下文提供器。搜索快照与执行期实时上下文
 * 共用同一个 SAFE_FOLLOW builder，不修改全局导航设置。
 */
public final class FollowContextProvider implements PlayerNav.ContextProvider {

    public static final FollowContextProvider INSTANCE = new FollowContextProvider();

    private static final ContextFactory.ContextBuilder SAFE_CONTEXT =
            (player, view, loadedTest, safeForThreadedUse, sacred, deniedPlace) ->
                    new CalculationContext(player, view, loadedTest, safeForThreadedUse,
                            sacred, deniedPlace, NavigationCapabilities.SAFE_FOLLOW);

    private FollowContextProvider() {}

    @Override
    public CalculationContext forSearch(NumenPlayer player, LongSet sacred, LongSet deniedPlace) {
        return ContextFactory.forSearch(player, sacred, deniedPlace, SAFE_CONTEXT);
    }

    @Override
    public CalculationContext forExecution(NumenPlayer player, LongSet sacred,
                                           LongSet deniedPlace) {
        return ContextFactory.forExecution(player, sacred, deniedPlace, SAFE_CONTEXT);
    }

    static NavigationCapabilities capabilities() {
        return NavigationCapabilities.SAFE_FOLLOW;
    }

    static ContextFactory.ContextBuilder contextBuilder() {
        return SAFE_CONTEXT;
    }
}
