package com.dwinovo.numen.pathing.spec;

import java.util.Set;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 路线规格:出厂值、改动、按位置与按种类的禁令。 */
class RouteSpecTest {

    private static final BlockPos AT = new BlockPos(0, 64, 0);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static boolean excludedByDefault(BlockState state) {
        return RouteSpec.defaults().excludesAny(Semantics.kinds(new TestWorld().set(AT, state), AT));
    }

    @Test
    void byDefaultTheRouteKeepsOffWhatWouldBeTrampledOrTriggered() {
        assertTrue(excludedByDefault(Blocks.FARMLAND.defaultBlockState()), "耕地");
        assertTrue(excludedByDefault(Blocks.TURTLE_EGG.defaultBlockState()), "海龟蛋");
        assertTrue(excludedByDefault(Blocks.STONE_PRESSURE_PLATE.defaultBlockState()), "压力板");
        assertTrue(excludedByDefault(Blocks.TRIPWIRE.defaultBlockState()), "绊线");
    }

    @Test
    void byDefaultTheRouteKeepsOutOfLavaAndHazardsButNotWaterDoorsOrLadders() {
        assertTrue(excludedByDefault(Blocks.LAVA.defaultBlockState()));
        assertTrue(excludedByDefault(Blocks.CACTUS.defaultBlockState()));
        assertTrue(excludedByDefault(Blocks.FIRE.defaultBlockState()));
        assertFalse(excludedByDefault(Blocks.WATER.defaultBlockState()));
        assertFalse(excludedByDefault(Blocks.OAK_DOOR.defaultBlockState()));
        assertFalse(excludedByDefault(Blocks.LADDER.defaultBlockState()));
        assertFalse(excludedByDefault(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void byDefaultTheRouteKeepsOutOfWaterThatPushesTheBody() {
        BlockState lower = Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 3);
        TestWorld stream = new TestWorld().set(AT, Blocks.WATER.defaultBlockState()).set(AT.east(), lower)
                .set(AT.below(), Blocks.STONE.defaultBlockState()).set(AT.east().below(), Blocks.STONE.defaultBlockState());
        assertTrue(RouteSpec.defaults().excludesAny(Semantics.kinds(stream, AT.east())), "流水把身体推离路线");
        assertTrue(RouteSpec.defaults().excludes(Kind.FLOWING_WATER));
        assertFalse(RouteSpec.defaults().edit().allow(Kind.FLOWING_WATER).build().excludes(Kind.FLOWING_WATER));
    }

    @Test
    void theDefaultsOnlyWalk() {
        RouteSpec d = RouteSpec.defaults();
        assertFalse(d.dig());
        assertFalse(d.place());
        assertFalse(d.changes());
        assertTrue(d.consent(), "要问的格算能走,许挖或许放时才用得上");
        assertEquals(RouteSpec.CONSENT_MULTIPLIER, d.consentMultiplier());
        assertFalse(d.budgeted());
        assertTrue(d.positions().isEmpty());
        assertTrue(d.bans().isEmpty());
    }

    @Test
    void aSpecCanAllowFarmlandAndExcludeWater() {
        RouteSpec farm = RouteSpec.defaults().edit().allow(Kind.FRAGILE).exclude(Kind.WATER).build();
        assertFalse(farm.excludes(Kind.FRAGILE));
        assertTrue(farm.excludes(Kind.WATER));
        assertTrue(RouteSpec.defaults().excludes(Kind.FRAGILE), "改一份不动出厂值");
        assertFalse(RouteSpec.defaults().excludes(Kind.WATER));
    }

    @Test
    void editingKeepsEverythingElse() {
        RouteSpec edited = RouteSpec.defaults().edit().changes(true).consent(false).alterBudget(4).build();
        assertTrue(edited.dig());
        assertTrue(edited.place());
        assertFalse(edited.consent());
        assertTrue(edited.budgeted());
        assertEquals(RouteSpec.defaults().jumpPenalty(), edited.jumpPenalty());
        assertEquals(RouteSpec.defaults().excluded(), edited.excluded());
        assertEquals(RouteSpec.defaults(), RouteSpec.defaults().edit().build(), "什么都不改就等于原来那份");
    }

    @Test
    void nonsenseValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RouteSpec.defaults().edit().jumpPenalty(-1).build());
        assertThrows(IllegalArgumentException.class,
                () -> RouteSpec.defaults().edit().placeCost(Double.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class, () -> RouteSpec.defaults().edit().alterBudget(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RouteSpec.defaults().edit().maxFallHeightNoWater(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RouteSpec.defaults().edit().consentMultiplier(0.5).build());
        assertThrows(IllegalArgumentException.class,
                () -> RouteSpec.defaults().edit().consentMultiplier(Double.POSITIVE_INFINITY).build());
    }

    @Test
    void protectedCellsMayBeStoodOnButNotDugOrFilled() {
        long cell = AT.asLong();
        PositionCosts p = PositionCosts.protect(LongSet.of(cell));
        assertTrue(p.forbids(Use.DIG, cell));
        assertTrue(p.forbids(Use.PLACE, cell));
        assertFalse(p.forbids(Use.STAND, cell));
        assertFalse(p.forbids(Use.PASS, cell));
        assertFalse(p.forbids(Use.DIG, AT.above().asLong()));
        assertSame(PositionCosts.EMPTY, PositionCosts.protect(LongSet.of()));
    }

    @Test
    void positionTablesAddUp() {
        long cell = AT.asLong();
        PositionCosts a = PositionCosts.builder().add(Use.STAND, cell, 5).forbid(Use.DIG, cell).build();
        PositionCosts b = PositionCosts.builder().add(Use.STAND, cell, 2).forbid(Use.PLACE, cell).build();
        PositionCosts sum = a.plus(b);
        assertEquals(7, sum.extra(Use.STAND, cell));
        assertEquals(0, sum.extra(Use.PASS, cell));
        assertTrue(sum.forbids(Use.DIG, cell));
        assertTrue(sum.forbids(Use.PLACE, cell));
        assertSame(a, a.plus(PositionCosts.EMPTY));
    }

    /** "只许这几格":这一栏别的格一律禁止,别的栏不受影响;两份"只许"叠在一起取交集,空集就是一格都不许。 */
    @Test
    void aConfinedUseForbidsEveryOtherCellAndTwoConfinementsIntersect() {
        long a = AT.asLong();
        long b = AT.above().asLong();
        long c = AT.east().asLong();
        PositionCosts only = PositionCosts.builder().confine(Use.DIG, LongSet.of(a, b)).build();
        assertFalse(only.forbids(Use.DIG, a));
        assertFalse(only.forbids(Use.DIG, b));
        assertTrue(only.forbids(Use.DIG, c));
        assertFalse(only.forbids(Use.PLACE, c), "只管挖这一栏");
        assertFalse(only.isEmpty());
        PositionCosts both = only.plus(PositionCosts.builder().confine(Use.DIG, LongSet.of(b, c)).build());
        assertTrue(both.forbids(Use.DIG, a));
        assertFalse(both.forbids(Use.DIG, b));
        assertTrue(both.forbids(Use.DIG, c));
        PositionCosts none = PositionCosts.builder().confine(Use.PLACE, LongSet.of()).build();
        assertTrue(none.forbids(Use.PLACE, a));
        assertTrue(only.plus(PositionCosts.protect(LongSet.of(a))).forbids(Use.DIG, a), "禁令照旧取并集");
    }

    /** 整片禁止:只问"在不在",几百万格也不逐格展开;只管给了的那一栏,合并时与逐格的禁令一样取并集。 */
    @Test
    void aForbiddenRegionIsAskedCellByCellWithoutBeingSpelledOut() {
        int[] asked = {0};
        // 一片 x ≥ 0 的半个世界:逐格展开根本装不下,判定只是比一个数
        PositionCosts.Region east = cell -> {
            asked[0]++;
            return BlockPos.getX(cell) >= 0;
        };
        PositionCosts half = PositionCosts.builder().forbid(Use.PASS, east).forbid(Use.STAND, east).build();
        assertFalse(half.isEmpty());
        assertTrue(half.forbids(Use.PASS, new BlockPos(3_000_000, 64, -7).asLong()));
        assertTrue(half.forbids(Use.STAND, AT.asLong()));
        assertFalse(half.forbids(Use.PASS, new BlockPos(-1, 64, 0).asLong()));
        assertFalse(half.forbids(Use.DIG, AT.asLong()), "只管给了的那几栏");
        assertTrue(asked[0] > 0);
        PositionCosts both = half.plus(PositionCosts.builder().forbid(Use.PASS, AT.west().asLong()).build());
        assertTrue(both.forbids(Use.PASS, AT.west().asLong()), "逐格的禁令照旧");
        assertTrue(both.forbids(Use.PASS, AT.asLong()), "整片的禁令合并后还在");
        assertFalse(both.forbids(Use.PASS, AT.west(2).asLong()));
    }

    @Test
    void aForbiddenCellIsNotAnInfinitePrice() {
        assertThrows(IllegalArgumentException.class,
                () -> PositionCosts.builder().add(Use.DIG, AT.asLong(), Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> PositionCosts.builder().add(Use.DIG, AT.asLong(), -1));
    }

    @Test
    void blockBansMergeColumnByColumn() {
        BlockBans a = new BlockBans(Set.of(Blocks.CHEST), Set.of(), Set.of(Blocks.WHEAT));
        BlockBans b = new BlockBans(Set.of(Blocks.OAK_LOG), Set.of(Blocks.WATER), Set.of());
        BlockBans sum = a.plus(b);
        assertEquals(Set.of(Blocks.CHEST, Blocks.OAK_LOG), sum.breaking());
        assertEquals(Set.of(Blocks.WATER), sum.placingInto());
        assertEquals(Set.of(Blocks.WHEAT), sum.standingOn());
        assertTrue(BlockBans.EMPTY.isEmpty());
    }
}
