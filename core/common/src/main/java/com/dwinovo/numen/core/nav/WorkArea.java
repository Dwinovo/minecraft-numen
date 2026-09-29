package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.pathing.search.WorldSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 工作区:一件就地干的活(挖矿、捡东西)只在这块地方里干。它是一块区域({@link Area}):以受理时她脚下那一格为中心、半径
 * {@code radius} 格的球({@link Cells#sphere},距离按两格的整数坐标算),在她受理时所在的维度里。一格在不在区里只问这块区域,
 * 与别的区域同一种判定;挖矿把点名的区域和它求交、求差,也是区域运算。区里的目标她自己走过去干,区外的只报告、不去;要不要离开
 * 这块地方是模型的决定。
 *
 * <p><b>半径的上限只有一个来源:寻路一次看得清的范围。</b>每次搜索拷贝以起点所在区块为中心、半径
 * {@link WorldSnapshot#SEARCH_RADIUS} 个区块的快照,所以从任何起点出发,水平方向至少 {@code SEARCH_RADIUS × 16}
 * 格以内都在这一份快照里。工作区的直径取这么大:区里任意两点相距不超过它,她站在区里任何地方朝区里任何目标搜,
 * 目标都在同一份快照里,一次规划就答得清"去得了还是去不了"。竖直方向快照整列都有,用同一个半径——这样区是个球。
 * 一次搜索还有展开节点的预算(默认四万):实心石头里挖 30 格隧道展开约一万一千个节点({@code docs/pathing.md} 第十三节),
 * 半径以内的竖井与横洞一般在预算里;超出时寻路如实报"预算用完",不说成无路。
 *
 * <p>站位不在区里也无妨:目标在区里就行,够得着它的站位可能在区边外一两格。
 *
 * @param center 中心:受理时她脚下那一格
 * @param radius 半径(格),不超过 {@link #RADIUS}
 * @param area   这块球形区域本身:一格在不在区里只问它
 */
public record WorkArea(BlockPos center, int radius, Area area) {

    /** 工作区半径的上限:一次搜索从任何起点都看得见的水平距离的一半,见类注释。 */
    public static final int RADIUS = SectionPos.sectionToBlockCoord(WorldSnapshot.SEARCH_RADIUS) / 2;

    public WorkArea {
        center = center.immutable();
    }

    /** 以 {@code body} 此刻脚下那一格为中心、半径取上限的工作区。 */
    public static WorkArea around(Entity body) {
        return around(body, RADIUS);
    }

    /**
     * 以 {@code body} 此刻脚下那一格为中心、半径 {@code radius} 的工作区。
     *
     * @throws IllegalArgumentException 半径不在 1 到 {@link #RADIUS} 之间
     */
    public static WorkArea around(Entity body, int radius) {
        return at(body.level().dimension(), body.blockPosition(), radius);
    }

    /**
     * {@code dimension} 里以 {@code center} 为中心、半径 {@code radius} 的工作区。
     *
     * @throws IllegalArgumentException 半径不在 1 到 {@link #RADIUS} 之间
     */
    public static WorkArea at(ResourceKey<Level> dimension, BlockPos center, int radius) {
        if (radius < 1 || radius > RADIUS) {
            throw new IllegalArgumentException("工作区半径要在 1 到 " + RADIUS + " 之间:" + radius);
        }
        return new WorkArea(center, radius, Area.of(dimension, Area.Kind.SPHERE, Cells.sphere(center, radius)));
    }

    /** 这一格在不在区里:同一个维度、落在那个球里。 */
    public boolean contains(ResourceKey<Level> dimension, BlockPos pos) {
        return area.contains(dimension, pos);
    }

    /** 区里的格子,给区域运算用(点名的区域与工作区求交、求差)。 */
    public Cells cells() {
        return area.cells();
    }

    /** 给模型读的一截:{@code within 48 blocks of 10,64,-3}。 */
    public String describe() {
        return "within " + radius + " blocks of " + center.getX() + "," + center.getY() + "," + center.getZ();
    }
}
