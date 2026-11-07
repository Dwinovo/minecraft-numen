package com.dwinovo.numen.pathing.benchmark;

import com.dwinovo.numen.pathing.plan.Mobility;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.api.entity.DigTime;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * 题目的两种规格,同一道题两种都跑。评测的身体、端口都在这里一处定,题库与成绩只由"题 + 规格"决定:
 * <ul>
 *   <li>A:不挖不垫({@link RouteSpec#defaults});</li>
 *   <li>B:出厂规格,可挖可垫,只看价钱({@code edit().changes(true)},与模型走路用的规格同一口径)。</li>
 * </ul>
 * 评测里真实的趟逻辑跑出厂罚分({@link #model});标准答案算物理口径({@link #physicalModel}),不含偏好。
 * 身体是原版生存模式的玩家:满血、吃饱、满氧气、没有任何药水或属性加成,背包第一格是一把钻石镐、身上带着圆石当垫路料
 * (A 不垫,不用)。许可什么都放行,没有要避开的生物。
 */
enum Spec {
    A, B;

    private static final Materials COBBLE = () -> Optional.of(Blocks.COBBLESTONE);
    private static final TerrainPolicy ALLOW_ALL = (change, pos, state, view) -> Permit.ALLOW;

    /** 标准答案的口径:Numen 的搜索按纯物理耗时(刻)算出的参考值,不是真最优(状态抽象见 ADR 0036);口径或走法变了就换序号。 */
    static final String PHYSICAL_MODEL = "numen-physical-1";

    /** 原版玩家的身体(单测用的同一份,见 {@link Vanilla})。 */
    static final BodyStats STATS = Vanilla.SURVIVAL;

    static BodySnapshot body() {
        List<ItemStack> inventory = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            inventory.add(i == 0 ? new ItemStack(Items.DIAMOND_PICKAXE) : ItemStack.EMPTY);
        }
        return new BodySnapshot(STATS, GameType.SURVIVAL, 20, 3, 1, 20, 0, inventory, new DigTime.Mining(0, 1, 0.2, -1, -1),
                new Breath(300, 300, 0, 0, false), Mobility.WALKER);
    }

    RouteSpec route() {
        return this == A ? RouteSpec.defaults() : RouteSpec.defaults().edit().changes(true).build();
    }

    /** 这种规格下评测里真实的趟逻辑用的成本模型(带出厂罚分)。 */
    CostModel model() {
        return CostModel.of(route(), body(), ALLOW_ALL, COBBLE, Threats.NONE);
    }

    /**
     * 标准答案的物理口径:同一份规格,放、挖、起跳、涉水与挨着伤身格走的罚分全清零,代价只剩物理耗时(刻)。答案里的价钱因此与罚分、权重无关,
     * 改了它们不必重算数据集;评测的次优比是路线的物理刻数除以这里的最优价。
     */
    RouteSpec physicalRoute() {
        return route().edit().placeCost(0).breakPenalty(0).jumpPenalty(0).wadePenalty(0).exposurePenalty(0).build();
    }

    /** 标准答案(出题与重算)用的成本模型:{@link #physicalRoute} 下的。 */
    CostModel physicalModel() {
        return CostModel.of(physicalRoute(), body(), ALLOW_ALL, COBBLE, Threats.NONE);
    }

    /** 这一格是不是路线规格排除的种类(岩浆、火、粉雪、流水、机关、易碎):出题挑起终点与中立地形的"禁入"类别按的是同一个判据。 */
    static boolean excludes(BlockGetter level, BlockPos pos) {
        return RouteSpec.defaults().excludes(level, pos);
    }

    /** 标准答案里一道题的目标:题的目标去掉挖一格时的遮挡偏好({@link Physical})。 */
    static Goal physicalGoal(Problem problem) {
        return Physical.of(problem.goal(STATS));
    }

    static Materials materials() {
        return COBBLE;
    }

    static TerrainPolicy policy() {
        return ALLOW_ALL;
    }
}
