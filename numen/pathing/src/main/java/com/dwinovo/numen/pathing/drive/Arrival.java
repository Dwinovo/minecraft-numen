package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;

/**
 * 到达:路线走完、身体在终点上停稳之后,到没到只看目标自己的判定。模块里没有"差不多到了"。
 * 这里只下结论,不碰身体:怎么停稳(按键、转向)是段状态机的事,停稳了才问这里。路过的导航({@link com.dwinovo.numen.pathing.api.NavRequest#through})
 * 不停稳:身体走到终点、在目标里就算到了,键按着交给下一次导航。
 */
final class Arrival {

    private Arrival() {}

    /** 身体({@code flying}:飞着)在 {@code node} 上、它就在目标里。停稳了问是到没到,路过的导航走到那儿问是算不算到了。 */
    static boolean arrived(Goal goal, WorldView world, BodyStats stats, BlockPos node, boolean flying) {
        Stance here = Stance.at(world, stats, node, flying);
        return here != null && goal.contains(node.getX(), node.getY(), node.getZ(), here);
    }
}
