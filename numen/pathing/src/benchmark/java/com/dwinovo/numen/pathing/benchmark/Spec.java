package com.dwinovo.numen.pathing.benchmark;

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
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * 题目的两种规格,同一道题两种都跑。评测的身体、端口都在这里一处定,题库与成绩只由"题 + 规格"决定:
 * <ul>
 *   <li>A:不挖不垫({@link RouteSpec#defaults});</li>
 *   <li>B:出厂规格,可挖可垫,只看价钱({@code edit().changes(true)},与模型走路用的规格同一口径)。</li>
 * </ul>
 * 身体是原版生存模式的玩家:满血、吃饱、满氧气、没有任何药水或属性加成,背包第一格是一把钻石镐、身上带着圆石当垫路料
 * (A 不垫,不用)。许可什么都放行,没有要避开的生物。
 */
enum Spec {
    A, B;

    private static final Materials COBBLE = () -> Optional.of(Blocks.COBBLESTONE);
    private static final TerrainPolicy ALLOW_ALL = (change, pos, state, view) -> Permit.ALLOW;

    /** 原版玩家的身体(单测用的同一份,见 {@link Vanilla})。 */
    static final BodyStats STATS = Vanilla.SURVIVAL;

    static BodySnapshot body() {
        List<ItemStack> inventory = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            inventory.add(i == 0 ? new ItemStack(Items.DIAMOND_PICKAXE) : ItemStack.EMPTY);
        }
        return new BodySnapshot(STATS, GameType.SURVIVAL, 20, 3, 1, 20, 0, inventory, new DigTime.Mining(0, 1, 0.2, -1, -1),
                new Breath(300, 300, 0, 0, false));
    }

    RouteSpec route() {
        return this == A ? RouteSpec.defaults() : RouteSpec.defaults().edit().changes(true).build();
    }

    /** 这种规格下搜索用的成本模型。 */
    CostModel model() {
        return CostModel.of(route(), body(), ALLOW_ALL, COBBLE, Threats.NONE);
    }

    static Materials materials() {
        return COBBLE;
    }

    static TerrainPolicy policy() {
        return ALLOW_ALL;
    }
}
