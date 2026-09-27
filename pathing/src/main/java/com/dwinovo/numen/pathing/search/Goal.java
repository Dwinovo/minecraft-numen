package com.dwinovo.numen.pathing.search;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.RouteSpec;

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
     * 停在这个已到达的节点上,还要看得见哪一格才算数(贴脸要够得着的那一格);不要求视线为 null。目标只判几何上够不够得着,
     * 视线由执行层到了之后在活世界上复核。
     */
    default BlockPos sight(int x, int y, int z, Stance stance) {
        return null;
    }

    /**
     * 为这个目标搜索与执行复核用的成本模型:把目标格保护({@link #protection()})并进路线规格的按位置禁令。搜索展开与
     * 执行时在活世界上复核前提,读的都是它,只此一处。
     */
    static CostModel guarded(Goal goal, CostModel model) {
        PositionCosts protection = goal.protection();
        if (protection.isEmpty()) {
            return model;
        }
        RouteSpec spec = model.spec();
        return model.withSpec(spec.edit().positions(spec.positions().plus(protection)).build());
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
