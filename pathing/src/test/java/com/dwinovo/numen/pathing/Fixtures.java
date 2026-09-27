package com.dwinovo.numen.pathing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.AStar;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** 规划与搜索单测的常用摆设:一具原版身体、一份成本模型、跑一次搜索。 */
public final class Fixtures {

    /** 单测默认的展开预算。 */
    public static final int BUDGET = 20_000;
    /** 身上带着圆石当垫路料。 */
    public static final Materials COBBLE = () -> Optional.of(Blocks.COBBLESTONE);

    private Fixtures() {}

    /** 满血、吃饱、背包空着的生存模式原版身体。 */
    public static BodySnapshot body() {
        return body(Vanilla.SURVIVAL, GameType.SURVIVAL, 20, List.of());
    }

    /** 同一具身体,血量是 {@code health}。 */
    public static BodySnapshot body(float health) {
        return body(Vanilla.SURVIVAL, GameType.SURVIVAL, health, List.of());
    }

    /** 背包里在这些槽位放着这些东西(其余空着)的身体。 */
    public static BodySnapshot carrying(int slot, ItemStack stack) {
        List<ItemStack> inventory = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            inventory.add(i == slot ? stack : ItemStack.EMPTY);
        }
        return body(Vanilla.SURVIVAL, GameType.SURVIVAL, 20, inventory);
    }

    public static BodySnapshot body(BodyStats stats, GameType mode, float health, List<ItemStack> inventory) {
        return new BodySnapshot(stats, mode, health, 3, 1, 20, 0, inventory, BodySnapshot.Mining.VANILLA);
    }

    /** 这份规格,原版身体,什么都放行,身上没料,没有生物。 */
    public static CostModel model(RouteSpec spec) {
        return CostModel.of(spec, body(), TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
    }

    /** 这份规格,原版身体,什么都放行,身上带着圆石。 */
    public static CostModel withCobble(RouteSpec spec) {
        return CostModel.of(spec, body(), TerrainPolicy.ALLOW_ALL, COBBLE, Threats.NONE);
    }

    /** 能改自然地形({@code alter=natural})的规格。 */
    public static RouteSpec natural() {
        return RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build();
    }

    /** 在这个世界里按这份成本模型从 {@code start} 搜到 {@code goal}。 */
    public static SearchResult search(TestWorld world, CostModel model, BlockPos start, Goal goal) {
        return search(world, model, start, goal, BUDGET);
    }

    public static SearchResult search(TestWorld world, CostModel model, BlockPos start, Goal goal, int budget) {
        return AStar.run(new Search(world, model, start, goal, budget, Favoring.NONE), () -> false);
    }
}
