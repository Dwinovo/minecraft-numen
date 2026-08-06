package com.dwinovo.numen.core.pathing.goals;

import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities.Threat;

/**
 * 走到一个目标跟前,<b>路上绕开别的威胁</b>。
 *
 * <h2>为什么要有</h2>
 * 纯跟随目标会挑最短的路,而最短的路常常从第二只怪身上碾过去——她去打一只骷髅,途中穿过
 * 三只僵尸,到了跟前血已经见底。走位本来就是战斗的一部分,不该等到"要逃了"才开始算。
 *
 * <h2>目标自己不进势场</h2>
 * 这是这个目标最容易写错的地方:她要打的那只<b>也是</b>敌对生物,若它也在势场里,它会把她
 * 推开——于是她永远走不到跟前,而估价还一路显示"在接近"。排除工作在调用方做,这里只负责
 * 把给进来的两样加起来。
 *
 * <h2>两项的量纲</h2>
 * 吸引项是<b>真实的走路成本</b>(与其它目标同源);排斥项是 {@link GoalAvoidEntities} 那片
 * 势场,靠 {@code penaltyFactor} 折进同一个量纲。调大势场她愿意多绕,调小则从怪边上擦过去。
 */
public class GoalApproachAvoiding implements Goal {

    private final Goal approach;
    private final GoalAvoidEntities repulsion;

    /**
     * @param approach  去哪儿(到达判定完全由它说了算——绕路不改变"到没到")
     * @param repulsion 路上躲谁;<b>不含 approach 的目标本身</b>
     */
    public GoalApproachAvoiding(Goal approach, GoalAvoidEntities repulsion) {
        this.approach = approach;
        this.repulsion = repulsion;
    }

    /** 是否有值得绕的东西——没有就别包这一层,省掉每个节点的势场计算。 */
    public static Goal wrap(Goal approach, double penaltyFactor, Threat... threats) {
        if (threats.length == 0) {
            return approach;
        }
        // 到达判定归 approach,所以势场这一层不需要自己的"拉开多远":给 0,
        // 它的 isInGoal 恒真,只剩估价起作用。
        return new GoalApproachAvoiding(approach,
                new GoalAvoidEntities(0.0, penaltyFactor, threats));
    }

    /** 到没到只问 approach。绕开谁不改变终点在哪。 */
    @Override
    public boolean isInGoal(int x, int y, int z) {
        return approach.isInGoal(x, y, z);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return approach.heuristic(x, y, z) + repulsion.heuristic(x, y, z);
    }

    @Override
    public String toString() {
        return "GoalApproachAvoiding{" + approach + " avoiding " + repulsion + "}";
    }
}
