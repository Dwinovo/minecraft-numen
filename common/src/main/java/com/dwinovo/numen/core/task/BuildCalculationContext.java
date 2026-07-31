package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.util.BlockHelper;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Set;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/** Cost context used while a construction task owns the path search. */
final class BuildCalculationContext extends CalculationContext {

    private final Map<Long, BuildTaskRecord.Target> activeTargets;
    private final Set<BlockState> availableStates;
    private final boolean replaceExisting;
    BuildCalculationContext(ServerPlayer player, BlockGetter view, ChunkLoadedTest loadedTest,
                            boolean safeForThreadedUse, LongSet sacred, LongSet deniedPlace,
                            Map<Long, BuildTaskRecord.Target> activeTargets,
                            Set<BlockState> availableStates, boolean replaceExisting) {
        super(player, view, loadedTest, safeForThreadedUse, sacred, deniedPlace);
        this.activeTargets = Map.copyOf(activeTargets);
        this.availableStates = Set.copyOf(availableStates);
        this.replaceExisting = replaceExisting;
        this.jumpPenalty += 10.0;
        this.backtrackCostFavoringCoefficient = 1.0;
    }

    @Override
    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        long key = BlockPos.asLong(x, y, z);
        BuildTaskRecord.Target target = activeTargets.get(key);
        if (target != null) {
            if (sacred.contains(key) || deniedPlace.contains(key)) {
                return COST_INF;
            }
            if (!MovementHelper.placeableWithinBorder(worldBorder, x, z)) {
                return COST_INF;
            }
            if (target.block() instanceof net.minecraft.world.level.block.AirBlock) {
                // 目标应为空气却被问能否在此放置(脚手架):恒计"放错块"有限成本,迟早还要挖掉。
                return placeBlockCost * NavSettings.get().placeIncorrectBlockPenaltyMultiplier;
            }
            if (target.matches(current)) {
                return COST_INF;
            }
            if (containsAvailableState(target.desiredState())
                    && MovementHelper.isReplaceable(x, y, z, current, loadedTest)) {
                return 0.0;
            }
            return hasThrowaway
                    ? placeBlockCost * 1.5 * NavSettings.get().placeIncorrectBlockPenaltyMultiplier
                    : COST_INF;
        }
        return super.costOfPlacingAt(x, y, z, current);
    }

    private boolean containsAvailableState(BlockState desired) {
        for (BlockState state : availableStates) {
            if (BuildValidity.sameBlockState(state, desired)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        long key = BlockPos.asLong(x, y, z);
        if (sacred.contains(key)) {
            return COST_INF;
        }
        if (BlockHelper.shouldAvoidBreaking(view, new BlockPos(x, y, z))) {
            return COST_INF;
        }
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        BuildTaskRecord.Target target = activeTargets.get(key);
        if (target != null) {
            if (target.matches(current)) {
                // 施工期拆已建对的格子收重罚:只有被自己的作品困住(封顶后人在
                // 屋里、门窗未通)这类无路可走的处境才值得付——玩家也是拆一块
                // 出去再补上。补墙由下一遍施工完成;游标模型没有即时回填反射,
                // 不存在逐 tick 拆补拉锯。交付后的过路(回撤)按普通格子计价。
                //
                // 罚金必须是"贵得不会顺手拆",而不是"贵到活路等于没有":逐层
                // 上盖时她本来就站在自己盖的楼板底下,唯一的出路就是拆一块钻
                // 出去。定价过高时 A* 宁可绕远,而屋里根本没有那么多远可绕,
                // 于是搜不出任何出路,只能返回半程路径让她原地打转——实测整栋
                // 房子就卡死在这里。取值参照:圆石徒手约十余刻,乘 8 约合绕行
                // 百格,足以让任何真实通路胜出,又不至于把"拆一块"排除在外。
                return 8.0;
            }
            return replaceExisting ? 1.0 : COST_INF;
        }
        return super.breakCostMultiplierAt(x, y, z, current);
    }
}