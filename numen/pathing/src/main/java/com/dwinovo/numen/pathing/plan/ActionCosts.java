package com.dwinovo.numen.pathing.plan;

/**
 * 代价里不是物理量的权衡,全模块只此一份。单位一律是刻(20 刻一秒)。物理耗时(走、跑、跳、落、爬要几刻)不在这里,
 * 在 {@link com.dwinovo.numen.pathing.world.Kinematics}。
 *
 * <p>这里是"值不值得":摔伤与要问许可的格子怎么折成刻、走进生物危险半径加多少。以"格平走的耗时"计的权衡只写格数(无量纲),
 * 乘上这具身体平走一格的刻数({@link com.dwinovo.numen.pathing.world.Kinematics#walkTicksPerBlock})才是刻。路线规格里的动作罚分
 * (放、挖、跳、涉水)与挨着伤身格走的加价是按次可调的,不在这张表里。搜索的估价不在这里,见 {@link com.dwinovo.numen.pathing.world.Kinematics#pace}。
 */
public final class ActionCosts {

    private ActionCosts() {}

    /** 落进倒下的水里之后,低头把水收回桶里:转头与原版两次右键之间的间隔。 */
    public static final double SCOOP_WATER = 10;

    // ==================== 罚分 ====================

    /** 摔掉一点血折多少刻:摔得起的高度也是路,只是疼;有不疼的走法时它自然让位。 */
    public static final double FALL_DAMAGE_PER_POINT = 20.0;
    /** 身体进入一只生物危险半径里的一格,加这么多格平走的耗时:穿过去约等于多绕十来格,够让路线绕开,又不至于宁可挖穿一座山。 */
    public static final double DANGER_BLOCKS = 3;
    /**
     * 挖一格时站位与它之间每隔着一格硬遮挡,停在那儿加这么多格平走的耗时:先得挖开它才看得见。约等于拿镐挖开一格石头再缓手的工夫;
     * 同样够得着的几个站位里挑挡得少的,多走三四格去一处挡得少的也值。
     */
    public static final double SIGHT_BLOCKER_BLOCKS = 4;
}
