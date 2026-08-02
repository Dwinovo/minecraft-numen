package com.dwinovo.numen.core.pathing.bridge;

import com.dwinovo.numen.core.pathing.cache.CachedNavView;
import com.dwinovo.numen.core.pathing.cache.LoadedChunks;
import com.dwinovo.numen.core.pathing.cache.PathCaches;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;

/**
 * 上下文工厂:把服务端快照体系接到新内核的 {@link CalculationContext}。
 *
 * <p>{@link #forSearch}(主线程调用)从 {@link PathCaches#ensureSnapshot}
 * 拿本维度的 {@link LoadedChunks} 快照,套上 {@link CachedNavView}
 * (逐格 memoize,未捕获 chunk 乐观按 AIR),chunk 加载谓词即快照
 * 捕获谓词;背包/附魔/饥饿/药水在 {@link CalculationContext} 与
 * {@code ToolSet} 构造时折成 final 字段。产出的上下文整体冻结,
 * 可交给 worker 线程跑完整场搜索。
 *
 * <p>{@link #forExecution}(主线程专用)直读活世界,供执行期逐 tick
 * 复核成本;{@code safeForThreadedUse=false},禁止交给 worker。
 */
public final class ContextFactory {

    private ContextFactory() {}

    @FunctionalInterface
    public interface ContextBuilder {
        CalculationContext create(ServerPlayer player, BlockGetter view, ChunkLoadedTest loadedTest,
                                  boolean safeForThreadedUse, LongSet sacred, LongSet deniedPlace);
    }

    /**
     * 搜索用冻结上下文。必须在主线程调用(快照补建与背包取样都要求
     * 主线程);返回后可交给 worker 线程只读使用。
     *
     * @param sacred      不可挖不可埋的自身目标格({@code BlockPos.asLong} 键)
     * @param deniedPlace 执行层证明放不上的格
     */
    public static CalculationContext forSearch(ServerPlayer player, LongSet sacred,
                                               LongSet deniedPlace) {
        return forSearch(player, sacred, deniedPlace, CalculationContext::new);
    }

    public static CalculationContext forSearch(ServerPlayer player, LongSet sacred,
                                               LongSet deniedPlace, ContextBuilder builder) {
        ServerLevel level = (ServerLevel) player.level();
        LoadedChunks loaded = PathCaches.ensureSnapshot(level, player.blockPosition());
        CachedNavView view = new CachedNavView(loaded, level);
        return builder.create(player, view, view::isLoaded, true, sacred, deniedPlace);
    }

    /** 无语义开关的搜索用冻结上下文。 */
    public static CalculationContext forSearch(ServerPlayer player) {
        return forSearch(player, LongSets.emptySet(), LongSets.emptySet());
    }

    /**
     * 执行期实时上下文:活世界的"只读已加载"视图——未加载区块读作
     * 空气,绝不触发同步加载/生成。主线程专用
     * ({@code safeForThreadedUse=false}),用于逐 tick 成本复核与
     * 装配期重算。
     */
    public static CalculationContext forExecution(ServerPlayer player, LongSet sacred,
                                                  LongSet deniedPlace) {
        return forExecution(player, sacred, deniedPlace, CalculationContext::new);
    }

    public static CalculationContext forExecution(ServerPlayer player, LongSet sacred,
                                                  LongSet deniedPlace, ContextBuilder builder) {
        var view = com.dwinovo.numen.core.pathing.cache.LoadedOnlyView.of(player.level());
        ChunkLoadedTest loaded = view instanceof com.dwinovo.numen.core.pathing.cache.LoadedOnlyView v
                ? v::isLoaded : ChunkLoadedTest.ALWAYS;
        return builder.create(player, view, loaded, false, sacred, deniedPlace);
    }

    /** 无语义开关的执行期实时上下文。 */
    public static CalculationContext forExecution(ServerPlayer player) {
        return forExecution(player, LongSets.emptySet(), LongSets.emptySet());
    }
}
