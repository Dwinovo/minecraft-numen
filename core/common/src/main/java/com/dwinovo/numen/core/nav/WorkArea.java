package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.pathing.search.WorldSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;

/**
 * 工作区:一件就地干的活(挖矿、捡东西)只在这块地方里干。它是以受理时她脚下那一格为中心、半径 {@code radius} 格的球,
 * 距离按两格的整数坐标算,与找方块的查询({@code BlockSearch})量距离是同一把尺。区里的目标她自己走过去干,区外的
 * 只报告、不去;要不要离开这块地方是模型的决定。
 *
 * <p><b>半径的上限只有一个来源:寻路一次看得清的范围。</b>每次搜索拷贝以起点所在区块为中心、半径
 * {@link WorldSnapshot#SEARCH_RADIUS} 个区块的快照,所以从任何起点出发,水平方向至少 {@code SEARCH_RADIUS × 16}
 * 格以内都在这一份快照里。工作区的直径取这么大:区里任意两点相距不超过它,她站在区里任何地方朝区里任何目标搜,
 * 目标都在同一份快照里,一次规划就答得清"去得了还是去不了"。竖直方向快照整列都有,用同一个半径——这样区是个球,
 * 与找方块的球形查询对得上。一次搜索还有展开节点的预算(默认四万):实心石头里挖 30 格隧道展开约一万一千个节点
 * ({@code docs/pathing.md} 第十三节),半径以内的竖井与横洞一般在预算里;超出时寻路如实报"预算用完",不说成无路。
 *
 * <p>站位不在区里也无妨:目标在区里就行,够得着它的站位可能在区边外一两格。
 *
 * @param center 中心:受理时她脚下那一格
 * @param radius 半径(格),不超过 {@link #RADIUS}
 */
public record WorkArea(BlockPos center, int radius) {

    /** 工作区半径的上限:一次搜索从任何起点都看得见的水平距离的一半,见类注释。 */
    public static final int RADIUS = SectionPos.sectionToBlockCoord(WorldSnapshot.SEARCH_RADIUS) / 2;

    public WorkArea {
        center = center.immutable();
        if (radius < 1 || radius > RADIUS) {
            throw new IllegalArgumentException("工作区半径要在 1 到 " + RADIUS + " 之间:" + radius);
        }
    }

    /** 以 {@code body} 此刻脚下那一格为中心、半径取上限的工作区。 */
    public static WorkArea around(Entity body) {
        return new WorkArea(body.blockPosition(), RADIUS);
    }

    /** 以 {@code body} 此刻脚下那一格为中心、半径 {@code radius} 的工作区。 */
    public static WorkArea around(Entity body, int radius) {
        return new WorkArea(body.blockPosition(), radius);
    }

    /** 这一格在不在区里。 */
    public boolean contains(BlockPos pos) {
        return center.distSqr(pos) <= (double) radius * radius;
    }

    /** 给模型读的一截:{@code within 48 blocks of 10,64,-3}。 */
    public String describe() {
        return "within " + radius + " blocks of " + center.getX() + "," + center.getY() + "," + center.getZ();
    }
}
