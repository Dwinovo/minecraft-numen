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
     * @param approach  去哪儿
     * @param repulsion 路上躲谁,以及<b>站定之后不许挨着谁</b>
     */
    public GoalApproachAvoiding(Goal approach, GoalAvoidEntities repulsion) {
        this.approach = approach;
        this.repulsion = repulsion;
    }

    /** 路上躲谁——搜索器要从这里取威胁表,把"走进去要多花钱"加到边成本上。 */
    public GoalAvoidEntities repulsion() {
        return repulsion;
    }

    /** 是否有值得绕的东西——没有就别包这一层。 */
    public static Goal wrap(Goal approach, double penaltyFactor, Threat... threats) {
        if (threats.length == 0) {
            return approach;
        }
        return new GoalApproachAvoiding(approach, new GoalAvoidEntities(penaltyFactor, threats));
    }

    /**
     * 到没到要<b>两项都点头</b>:走到了目标跟前,而且脚下这一格不在任何一只的危险半径里。
     *
     * <p>只问 approach 的时候,"绕开谁"只影响<b>路线</b>、不影响<b>落脚点</b>——她一走进
     * 目标的球形邻域就判到达,哪怕那一格正被另外三只怪围着。而一旦到达,A* 再不搜索,势场
     * 那份估价一次也用不上。站位好不好是到达条件的一部分,不是估价的偏好。
     *
     * <p>光有这一条还不够:目标是<b>开路那一刻的快照</b>,别的怪走动不会让它自己失效。
     * 快照由 {@code PlayerNav} 的活目标节拍定期重取,两件事缺一不可。
     */
    @Override
    public boolean isInGoal(int x, int y, int z) {
        return approach.isInGoal(x, y, z) && repulsion.isInGoal(x, y, z);
    }

    /**
     * <b>纯距离</b>。躲开谁不进估价,进的是<b>边成本</b>——走进一只怪的危险半径,那一步本身
     * 就贵({@code Avoidance.forGoal})。
     *
     * <h2>为什么不能加在这儿</h2>
     * 估价必须是剩余成本的下界,A* 才敢剪枝。而"离怪多近"这一项<b>不随接近目标而下降</b>,
     * 往人堆里走时还会反向增长。把它加进来就得在"强到能绕开"和"弱到不搞坏搜索"之间挑,
     * 两头都做不到:取 800 那次连两格的退路都算不出来({@code NO-PATH}),压到 15 又拦不住
     * 她从大史莱姆身上穿过去。
     *
     * <p>搬到边成本之后这个矛盾消失:A* 会逐格累加,穿过去每一格都付钱,而估价保持可采纳。
     */
    @Override
    public double heuristic(int x, int y, int z) {
        return approach.heuristic(x, y, z);
    }

    @Override
    public String toString() {
        return "GoalApproachAvoiding{" + approach + " avoiding " + repulsion + "}";
    }
}
