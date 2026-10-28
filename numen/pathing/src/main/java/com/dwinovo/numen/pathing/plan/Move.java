package com.dwinovo.numen.pathing.plan;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 一种走法:只有前提与代价两个纯函数。都不接触实体与活世界之外的东西——读的是传进来的只读视图、第 0 层与成本模型,
 * 所以搜索在快照上调它们,执行复核在活世界上调同一份。
 */
public interface Move {

    MoveKind kind();

    /** 这种走法从一个节点出发可以朝哪些方向。 */
    List<Heading> headings();

    /**
     * 身体此刻以 {@code stance} 待在节点 {@code from},朝 {@code heading} 走这一步,前提成不成立;成立时交出这一步的全部事实,
     * 包括执行时要挖、要放、要开关的格。
     */
    Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading);

    /**
     * 同 {@link #premise},只读节点四周的标志字({@link Quick}),不建 {@link Maneuver}、不问许可:断定前提必然不成立答
     * {@link Quick#REJECT},必然成立答 {@link Quick#HOLDS}(落点、代价、刻数在 {@code quick} 里),其余答 {@link Quick#LIKELY}
     * (落点与不高于精确代价的乐观代价在 {@code quick} 里),搜索取出这一步时再走精确的 {@link #premise}。三种答复必须与
     * {@link #premise} 相容,单测在随机世界上逐节点逐方向核对。
     */
    int quick(Quick quick, Heading heading);

    /** 前提成立的这一步要多少刻:身体移动本身,加上 {@link CostModel#overhead} 里每种走法都一样加的那部分。 */
    double cost(CostModel model, Maneuver maneuver);

    /**
     * 身体真做完这一步要几刻:移动本身,加上手上的活({@link CostModel#workTicks}:挖到碎连同缓手、倒水收水),不含规格的罚分、
     * 按位置的加价、摔疼的折价。憋气按它算({@link Breath#after})。
     */
    double ticks(CostModel model, Maneuver maneuver);
}
