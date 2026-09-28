package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.drive.LiveWorld;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Origin;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;

/**
 * 她此刻在哪个节点上、怎么待着——与寻路定起点、认步用的是同一条规则({@link Origin}、{@link Stance}),任务问"我是不是已经
 * 站在该站的地方"时也问这里,不另判。
 *
 * @param node   脚所在的节点(身体站在边沿时是托着它的那一列)
 * @param stance 在那个节点上怎么待着
 */
public record Feet(BlockPos node, Stance stance) {

    /** 她此刻的节点;悬在半空、卡在方块里时为 null。 */
    public static Feet of(ServerPlayer body) {
        LiveWorld world = new LiveWorld(body.serverLevel());
        var stats = Snapshots.stats(body);
        BlockPos node = Origin.of(world, stats, body.getX(), body.getY(), body.getZ()).orElse(null);
        if (node == null) {
            return null;
        }
        Stance stance = Stance.at(world, stats, node);
        return stance == null ? null : new Feet(node, stance);
    }

    /** 脚的高度归在的那一格(不管待不待得住)。 */
    public static BlockPos cell(ServerPlayer body) {
        return BlockPos.containing(body.getX(), Footing.cellOf(body.getY()), body.getZ());
    }

    /**
     * 按 {@code spec} 走的路线会不会让她在节点 {@code pos} 上站着:身体站得住、是站着({@link Stance}),脚所在、头所在、脚下那一格
     * 都不是这份规格排除的种类。找一处站着干活的地方(钓鱼)、给主人画周围哪儿能站(环视)问的都是它。
     */
    public static boolean standingSpot(BlockGetter level, BodyStats body, BlockPos pos, RouteSpec spec) {
        Stance stance = Stance.at(level, body, pos);
        if (stance == null || !stance.grounded()) {
            return false;
        }
        return !Semantics.isAny(level, pos, spec.excluded()) && !Semantics.isAny(level, pos.above(), spec.excluded())
                && !Semantics.isAny(level, stance.support(pos.getX(), pos.getZ()), spec.excluded());
    }

    /** 站在这里算不算到了 {@code goal}。 */
    public boolean in(Goal goal) {
        return goal.contains(node.getX(), node.getY(), node.getZ(), stance);
    }
}
