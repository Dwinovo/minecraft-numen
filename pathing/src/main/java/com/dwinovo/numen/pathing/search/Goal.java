package com.dwinovo.numen.pathing.search;

import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.spec.PositionCosts;

import net.minecraft.core.BlockPos;

/**
 * 搜索要去哪:哪些节点算到了、从一个节点估计还要多少刻、停在那儿还要付多少、为了到得了别动哪几格。模块里只有这一族目标
 * (由 {@link Goals} 编出),到没到只看 {@link #contains},没有"差不多到了"。
 */
public interface Goal {

    /** 身体以 {@code stance} 待在节点 {@code (x, y, z)} 上,算不算到了。 */
    boolean contains(int x, int y, int z, Stance stance);

    /** 从节点 {@code (x, y, z)} 到目标的估价(刻)。 */
    double estimate(int x, int y, int z);

    /** 停在这个已到达的节点之后还要付的价钱(刻),默认 0。多个成员各带各的价时,搜索按"走过去加到了再付"挑终点。 */
    default double arrival(int x, int y, int z, Stance stance) {
        return 0;
    }

    /**
     * 为了到得了这个目标,搜索不能动的格:别挖自己要站、要够的那一格,也别拿方块把它埋了。这是规划的正确性约束,
     * 不是权限;搜索把它并进路线规格的按位置禁令。
     */
    default PositionCosts protection() {
        return PositionCosts.EMPTY;
    }

    /**
     * 按 {@code before} 定下的"停在 {@code stop}",换成 {@code after} 之后还算不算数:还在新目标里,而且停在那儿没有变贵。
     * 目标换了(或跟着的东西挪了)之后,在走的路要不要重新搜,只问这一条。
     */
    static boolean keepsStop(Goal before, Goal after, BlockPos stop, Stance stance) {
        int x = stop.getX();
        int y = stop.getY();
        int z = stop.getZ();
        return after.contains(x, y, z, stance) && after.arrival(x, y, z, stance) <= before.arrival(x, y, z, stance);
    }
}
