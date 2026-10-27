package com.dwinovo.numen.nav;

import com.dwinovo.numen.api.permission.PlacedBlocks;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.server.level.ServerLevel;

/**
 * 走路的规格:模型的 {@code numen.move.to}、{@code away}、{@code follow} 和动手的活替自己走过去都用这一份,只此一处。
 *
 * <p>默认可挖可搭,只看价钱——能走就不挖,绕太远才挖;垫路用出厂的 {@code numen:throwaway} 料({@link ThrowawayBlocks#factory})。
 * 能不能动哪一格不在这里判:权限层说禁止的格规划时是墙,要问的格加价、动手那一刻才问,主人不答应的格这一趟里当墙
 * ({@link Trip})。观察模式下权限层拒一切改动,她自然只走。
 *
 * <p>建造的方块不挖:{@link PlacedBlocks} 记着的建造记号({@link PlacedBlocks.Way#BUILT}:她自己放的墙、主人盖的房子、别的玩家的建筑,
 * 谁放的都记),路线只许绕开、从上面走或站在旁边够。房子是要留下来的东西,不是挡路的地形:路上挖掉一格墙,下一轮又得补上,建造就在
 * 原地来回打转。走路自己为过去垫的(搭桥、垫柱、垫脚,记号是 {@link PlacedBlocks.Way#PATH},谁垫的都一样)不在此列:那是她为这一趟铺的,
 * 回程要拆照常经权限层裁决、照常报告;走路规则不禁不等于权限放行。这只管走路顺手挖;模型明确用 {@code numen.work.dig} 挖哪一格照旧,
 * 由权限层裁决。
 */
public final class Walking {

    private Walking() {}

    /** 她在 {@code level} 里走路的规格。在主线程上调(记号表在搜索线程上只读)。 */
    public static RouteSpec spec(ServerLevel level) {
        PlacedBlocks placed = PlacedBlocks.of(level);
        return RouteSpec.defaults().edit().changes(true)
                .positions(PositionCosts.builder().forbid(PositionCosts.Use.DIG, placed::isBuilt).build())
                .build();
    }
}
