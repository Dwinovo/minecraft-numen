package com.dwinovo.numen.pathing.search;

import java.util.Set;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Sight;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

/**
 * 搜索要去哪:哪些节点算到了、从一个节点估计还要多少刻、停在那儿还要付多少、为了到得了别动哪几格、到了还要看得见什么。
 * 模块里只有这一族目标(由 {@link Goals} 编出),到没到只看 {@link #contains},没有"差不多到了"。
 */
public interface Goal {

    /** 身体以 {@code stance} 待在节点 {@code (x, y, z)} 上,算不算到了。 */
    boolean contains(int x, int y, int z, Stance stance);

    /** 从节点 {@code (x, y, z)} 到目标的估价(刻)。 */
    double estimate(int x, int y, int z);

    /**
     * 停在这个已到达的节点之后还要付的价钱(刻),默认 0。多个成员各带各的价时,搜索按"走过去加到了再付"挑终点。
     * 无穷大是停在这儿也办不成(挖一格时看得见它的每一面都隔着挖的一方清不掉的格):搜索不在这里停,换目标也不认这个停点。
     * {@code level} 是身体停在那儿时面对的世界:搜索时是快照(叠着走到那儿那一步的改动),换目标时是活世界。
     */
    default double arrival(WorldView level, int x, int y, int z, Stance stance) {
        return 0;
    }

    /**
     * 到了时脚可能在哪些节点({@link BlockPos#asLong}):一个有限的超集,可以多、不能少。搜索拿它从目标往外算"至少还要挖开
     * 多少"({@link Burial})。某一列、某一高度、距离范围、远离这些没有边的目标为 null。
     */
    default LongSet endCells() {
        return null;
    }

    /**
     * 为了到得了这个目标,搜索不能动的格:别挖自己要站、要够的那一格,也别拿方块把它埋了。这是规划的正确性约束,
     * 不是权限;搜索把它并进路线规格的按位置禁令。
     */
    default PositionCosts protection() {
        return PositionCosts.EMPTY;
    }

    /**
     * 停在这个已到达的节点上,还要看得见什么才算数(用一格方块:它的哪几面);不要求视线为 null。搜索按快照挑出看得见的站位,
     * 执行层到了之后在活世界上用同一个视线函数复核。
     */
    default Sighting sight(int x, int y, int z, Stance stance) {
        return null;
    }

    /**
     * 到了还要看得见的:{@code target} 的 {@code faces} 里任何一面。
     */
    record Sighting(BlockPos target, Set<Direction> faces) {

        public Sighting {
            faces = Set.copyOf(faces);
        }

        /**
         * 从 {@code eye} 用得上它的哪一面(第 0 层 {@link Sight#use}):交出第一面的视线,可能还隔着软遮挡;一面都用不上为 null。
         */
        public Sight.Trace seen(BlockGetter level, Vec3 eye, double reach) {
            for (Direction face : Direction.values()) {
                if (faces.contains(face)) {
                    Sight.Trace trace = Sight.use(level, eye, reach, target, face);
                    if (trace != null) {
                        return trace;
                    }
                }
            }
            return null;
        }
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
     * 按 {@code before} 定下的"停在 {@code stop}",换成 {@code after} 之后还算不算数:还在新目标里、停在那儿办得成,而且没有
     * 变贵。目标换了(或跟着的东西挪了)之后,在走的路要不要重新搜,只问这一条。{@code level} 是此刻的世界。
     */
    static boolean keepsStop(WorldView level, Goal before, Goal after, BlockPos stop, Stance stance) {
        int x = stop.getX();
        int y = stop.getY();
        int z = stop.getZ();
        if (!after.contains(x, y, z, stance)) {
            return false;
        }
        double now = after.arrival(level, x, y, z, stance);
        return Double.isFinite(now) && now <= before.arrival(level, x, y, z, stance);
    }
}
