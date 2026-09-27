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
        assertEquals(RouteSpec.Alter.NONE, d.alter());
        assertFalse(d.alter().mayAlter());
        assertFalse(d.budgeted());
        assertFalse(d.takeBack());
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
        RouteSpec edited = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).alterBudget(4).build();
        assertEquals(RouteSpec.Alter.NATURAL, edited.alter());
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
