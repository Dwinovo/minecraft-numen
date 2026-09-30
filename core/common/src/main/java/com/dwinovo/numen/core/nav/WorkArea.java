package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 工作区:一件就地干的活(挖、捡)只在这块地方里干,身体也关在里面。它是一块区域({@link Area}):挖与捡用受理时她脚下那一格为
 * 中心、半径 {@link #RADIUS} 的球({@link #around});建造清场用工地外扩 {@link #SITE_MARGIN} 格的盒子({@link #site})。
 * 一格在不在区里只问这块区域,与别的区域同一种判定;挖的一方把点名的区域和它求交、求差,也是区域运算。区里的目标她自己挪几步
 * 去干,区外的只报告、不去:去远处是一条路线(先规划、再走),要不要走是模型的决定。
 *
 * <p><b>移动关在区里</b>:寻路的"只许"({@link PositionCosts.Builder#confine})把站、过、挖、放都限在区里的格上({@link #confine})。
 * 开不出远路是结构上做不到,不另写检查;一次搜索展开的节点也不会多过区里的格数,预算自然就小。
 *
 * <p><b>半径 10 的理由</b>:原版一团矿(矿石特征按大小沿一段线撒的几个小球)常见的铁、金、钻石、红石、青金石那一档横竖不过四五格;
 * 她用 {@code --arrive dig} 走到手够得着最近那一格(交互距离 4.5 格)的地方再开工,一团的另一头就在 4.5 + 4.5 ≈ 9 格内;挖碎的方块
 * 掉落物会弹开一格上下,再留一格。更大的一团(煤、铜)另一头可能落在区外,回执照实说,下一步照抄。
 *
 * @param center 给人说"离哪儿多远"的那一格:球是受理时她脚下那一格,工地是盒子的中心
 * @param area   这块区域本身:一格在不在区里只问它
 * @param where  给模型读的一截:{@code within 10 blocks of 10,64,-3}
 */
public record WorkArea(BlockPos center, Area area, String where) {

    /** 挖、捡的工作区半径(格),理由见类注释。 */
    public static final int RADIUS = 10;

    /** 建造清场的工作区在工地包围盒外多留几格:站到工地边上挖,也走得进挖开的坑。 */
    public static final int SITE_MARGIN = 2;

    public WorkArea {
        center = center.immutable();
    }

    /** 以 {@code body} 此刻脚下那一格为中心、半径 {@link #RADIUS} 的球。 */
    public static WorkArea around(Entity body) {
        return around(body.level().dimension(), body.blockPosition());
    }

    /** {@code dimension} 里以 {@code center} 为中心、半径 {@link #RADIUS} 的球。 */
    public static WorkArea around(ResourceKey<Level> dimension, BlockPos center) {
        return new WorkArea(center, Area.of(dimension, Area.Kind.SPHERE, Cells.sphere(center, RADIUS)),
                "within " + RADIUS + " blocks of " + coords(center));
    }

    /** 一处工地:{@code min}..{@code max} 这个盒子向外多留 {@link #SITE_MARGIN} 格。 */
    public static WorkArea site(ResourceKey<Level> dimension, BlockPos min, BlockPos max) {
        Cells box = Cells.box(min, max).grow(SITE_MARGIN);
        return new WorkArea(new BlockPos((min.getX() + max.getX()) / 2, (min.getY() + max.getY()) / 2,
                (min.getZ() + max.getZ()) / 2), Area.of(dimension, Area.Kind.BOX, box),
                "the site " + coords(min) + ".." + coords(max) + " and " + SITE_MARGIN + " blocks around it");
    }

    /** 这一格在不在区里:同一个维度、落在那块区域里。 */
    public boolean contains(ResourceKey<Level> dimension, BlockPos pos) {
        return area.contains(dimension, pos);
    }

    /**
     * 身体站得进 {@code feet} 这一格而不出区:脚那一格、头那一格与脚下踩着的那一格都在区里——移动关在区里时,站一格要这三格
     * 都许({@link #confine} 把"过"与"站"都限在区里的格上)。区边上的一格自己在区里,头顶那一格却可能出了区。
     */
    public boolean holdsBody(ResourceKey<Level> dimension, BlockPos feet) {
        return contains(dimension, feet) && contains(dimension, feet.above()) && contains(dimension, feet.below());
    }

    /** 区里的格子,给区域运算用(点名的区域与工作区求交、求差)。 */
    public Cells cells() {
        return area.cells();
    }

    /** 给模型读的一截:{@code within 10 blocks of 10,64,-3}。 */
    public String describe() {
        return where;
    }

    /**
     * {@code spec} 关进区里:站、过、挖、放都只许在区里的格上。与规格已有的按位置代价合并(同一栏的"只许"取交集)。
     */
    public RouteSpec confine(RouteSpec spec) {
        LongOpenHashSet cells = new LongOpenHashSet((int) Math.min(Integer.MAX_VALUE, area.cells().size()));
        area.cells().forEach((x, y, z, seen) -> cells.add(BlockPos.asLong(x, y, z)));
        PositionCosts.Builder only = PositionCosts.builder();
        for (PositionCosts.Use use : PositionCosts.Use.values()) {
            only.confine(use, cells);
        }
        return spec.edit().positions(spec.positions().plus(only.build())).build();
    }

    private static String coords(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
