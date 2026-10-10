package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Sight;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 到达:路线走完、身体在终点上停稳之后,到没到只看目标自己的判定,目标要求看得见某一格时在活世界上复核视线。模块里没有"差不多到了"。
 * 这里只下结论,不碰身体:怎么停稳(按键、转向)是段状态机的事,停稳了才问这里。路过的导航({@link com.dwinovo.numen.pathing.api.NavRequest#through})
 * 不停稳:身体走到终点、在目标里就算到了,键按着交给下一次导航。
 */
final class Arrival {

    /** 停稳之后的结论。 */
    sealed interface Verdict {
        /** 到了;要看着某一点时给出它。 */
        record Arrived(Vec3 aim) implements Verdict {}

        /** 停在这儿到不了:世界变了或终点不在目标里,按活世界重搜。 */
        record Replan(String why) implements Verdict {}

        /** 在目标里,可从眼睛此刻的位置看不见目标要看的那一格。 */
        record Blind(BlockPos target) implements Verdict {}
    }

    private Arrival() {}

    /** 路过的导航:身体走到 {@code node}、它就在目标里,就算到了。 */
    static boolean passes(Goal goal, WorldView world, BodyStats stats, BlockPos node) {
        Stance passed = Stance.at(world, stats, node);
        return passed != null && goal.contains(node.getX(), node.getY(), node.getZ(), passed);
    }

    /**
     * 身体在 {@code node} 停稳了:按目标自己的判定下结论。
     *
     * @param eye   眼睛此刻的位置
     * @param reach 交互距离
     */
    static Verdict judge(Goal goal, WorldView world, BodyStats stats, BlockPos node, Vec3 eye, double reach) {
        Stance here = Stance.at(world, stats, node);
        if (here == null || !goal.contains(node.getX(), node.getY(), node.getZ(), here)) {
            return new Verdict.Replan("停在终点 " + PathLog.pos(node) + " 却不在目标 " + goal + " 里");
        }
        if (!Double.isFinite(goal.arrival(world, node.getX(), node.getY(), node.getZ(), here))) {
            // 规划之后世界变了,停在这儿办不成了(挖一格时看得见它的面都隔着清不掉的格):按活世界重搜,换一处站位
            return new Verdict.Replan("停在终点 " + PathLog.pos(node) + " 却办不成 " + goal);
        }
        Goal.Sighting sighting = goal.sight(node.getX(), node.getY(), node.getZ(), here);
        if (sighting == null) {
            return new Verdict.Arrived(null);
        }
        // 用搜索挑站位时同一个视线函数复核;看得见就转过去看着那一面
        Sight.Trace seen = sighting.seen(world, eye, reach);
        return seen == null ? new Verdict.Blind(sighting.target()) : new Verdict.Arrived(seen.point());
    }
}
