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

    /** 前提成立的这一步要多少刻:身体移动本身,加上 {@link CostModel#overhead} 里每种走法都一样加的那部分。 */
    double cost(CostModel model, Maneuver maneuver);

    /**
     * 身体真做完这一步要几刻:移动本身,加上手上的活({@link CostModel#workTicks}:挖到碎连同缓手、倒水收水),不含规格的罚分、
     * 按位置的加价、摔疼的折价。憋气按它算({@link Breath#after})。
     */
    double ticks(CostModel model, Maneuver maneuver);
}
