package com.dwinovo.numen.pathing.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 步态:每一步疾不疾跑、收不收脚,只看路线上相邻两步的事实与脚下的世界;不经 {@link Driver}。 */
class GaitTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 窗口只有 {@code m} 和 {@code next} 两步时 {@code m} 的步态。 */
    private static Gait.Stride stride(TestWorld world, boolean maySprint, Maneuver m, Maneuver next) {
        return Gait.stride(world, Vanilla.SURVIVAL, maySprint, next == null ? List.of(m) : List.of(m, next));
    }

    /** 这条路线每一步的步态(每一步看得到后面的四步),最后一步没有下一步。 */
    private static List<Gait.Stride> strides(TestWorld world, List<Maneuver> steps, boolean maySprint) {
        List<Gait.Stride> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            out.add(Gait.stride(world, Vanilla.SURVIVAL, maySprint, steps.subList(i, Math.min(steps.size(), i + Continuation.TAIL))));
        }
        return out;
    }

    private static List<Maneuver> route(TestWorld world, CostModel model, BlockPos from, BlockPos to) {
        Route route = Fixtures.search(world, model, from, Goals.at(to)).route();
        return route.legs().stream().map(Route.Leg::maneuver).toList();
    }

    private static List<Maneuver> route(TestWorld world, BlockPos from, BlockPos to) {
        return route(world, Fixtures.model(RouteSpec.defaults()), from, to);
    }

    private static TestWorld flat() {
        return new TestWorld().floor(-4, -8, 40, 8, Y - 1);
    }

    private static Maneuver firstOf(MoveKind kind, List<Maneuver> steps) {
        return steps.stream().filter(m -> m.kind() == kind).findFirst().orElseThrow(() -> new AssertionError("路线里没有 " + kind));
    }

    @Test
    void aStraightRunSprintsEveryStepAndCarriesItsMomentumUntilTheLastOne() {
        TestWorld world = flat();
        List<Gait.Stride> strides = strides(world, route(world, new BlockPos(0, Y, 0), new BlockPos(12, Y, 0)), true);
        assertEquals(12, strides.size());
        assertTrue(strides.stream().allMatch(Gait.Stride::sprint));
        assertTrue(strides.subList(0, 11).stream().allMatch(Gait.Stride::flows), "连贯直行,冲劲带着进下一步");
        assertFalse(strides.get(11).flows(), "最后一步在落点上收脚");
    }

    @Test
    void aHungryBodyNeverSprints() {
        TestWorld world = flat();
        List<Gait.Stride> strides = strides(world, route(world, new BlockPos(0, Y, 0), new BlockPos(6, Y, 0)), false);
        assertTrue(strides.stream().noneMatch(Gait.Stride::sprint));
        assertTrue(strides.get(0).flows(), "不疾跑也一样连贯");
    }

    @Test
    void climbingStairsOfFullBlocksRunsUphillWithoutStoppingAtEachStep() {
        TestWorld world = flat();
        // 台阶之间隔着三格平地:落点前面没有墙贴着,余量够
        for (int i = 1; i <= 4; i++) {
            world.fill(i * 4, Y, -8, 40, Y + i - 1, 8, Blocks.STONE.defaultBlockState());
        }
        List<Maneuver> steps = route(world, new BlockPos(0, Y, 0), new BlockPos(16, Y + 4, 0));
        List<Gait.Stride> strides = strides(world, steps, true);
        assertTrue(steps.stream().anyMatch(m -> m.kind() == MoveKind.ASCEND), "有上一级");
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).kind() == MoveKind.ASCEND || steps.get(i).kind() == MoveKind.WALK) {
                assertTrue(strides.get(i).sprint(), "第 " + i + " 步 " + steps.get(i).kind() + " 物理上跑得起来,就跑(起跳不打断疾跑)");
            }
        }
        for (int i = 0; i + 1 < steps.size(); i++) {
            assertTrue(strides.get(i).flows(), "上坡一路连贯:第 " + i + " 步");
        }
    }

    @Test
    void goingDownASlopeOnlyCarriesTheDropWhenTheNextStepKeepsTheSameWay() {
        TestWorld world = flat();
        for (int k = 0; k < 4; k++) {
            world.fill(2 * k, Y, -8, 2 * k + 1, Y + 3 - k, 8, Blocks.STONE.defaultBlockState());
        }
        List<Maneuver> steps = route(world, new BlockPos(0, Y + 4, 0), new BlockPos(12, Y, 0));
        List<Gait.Stride> strides = strides(world, steps, true);
        boolean seen = false;
        for (int i = 0; i + 1 < steps.size(); i++) {
            if (steps.get(i).kind() == MoveKind.DESCEND) {
                seen = true;
                assertFalse(strides.get(i).sprint(), "走出边沿的下一级压着速度,不在跑动里");
                assertTrue(strides.get(i).flows(), "同一个方向接着走,冲过了头也还在路上");
            }
        }
        assertTrue(seen, "路线里有下一级");
    }

    @Test
    void aDropFollowedByATurnStopsAtTheLanding() {
        // 下一级之后紧接着朝垂直的方向走:离地的冲劲带着身子往前飘,转向接不住
        TestWorld world = new TestWorld().floor(0, 0, 3, 0, Y).floor(4, -8, 4, 0, Y - 1);
        List<Maneuver> steps = route(world, new BlockPos(0, Y + 1, 0), new BlockPos(4, Y, -8));
        int at = steps.indexOf(firstOf(MoveKind.DESCEND, steps));
        Maneuver descend = steps.get(at);
        Maneuver after = steps.get(at + 1);
        assertTrue(descend.heading().dx() != after.heading().dx() || descend.heading().dz() != after.heading().dz(), "下一步转了向");
        assertFalse(stride(world, true, descend, after).flows());
    }

    @Test
    void theStepBeforeAnInPlaceEditStopsAndTheEditStepDoesNotRun() {
        TestWorld world = flat().fill(5, Y, -8, 5, Y + 1, 8, Blocks.STONE.defaultBlockState());
        List<Maneuver> steps = route(world, Fixtures.model(Fixtures.natural()), new BlockPos(0, Y, 0), new BlockPos(8, Y, 0));
        List<Gait.Stride> strides = strides(world, steps, true);
        int editing = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).edits().isEmpty()) {
                editing = i;
                break;
            }
        }
        assertTrue(editing > 0, "路线要先挖开那堵墙");
        assertFalse(strides.get(editing).sprint(), "站着挖完再起步,这一步跑不起来");
        assertFalse(strides.get(editing - 1).flows(), "要原地改地形前收脚");
    }

    @Test
    void aRightAngleTurnStopsButADiagonalTurnCarries() {
        // 落点前一格是堵墙,格里往前只剩 0.15 格的余量:直角要停下全部速度(滑出去 0.21),停不住;斜着拐四十五度只有侧向那一份(0.12),停得住
        TestWorld world = new TestWorld().floor(-4, -4, 4, 4, Y - 1).fill(2, Y, 0, 2, Y + 1, 0, Blocks.STONE.defaultBlockState());
        Maneuver east = walk(world, 1, 0);
        assertFalse(stride(world, true, east, walk(world, 0, -1)).flows(), "直角弯收脚");
        assertTrue(stride(world, true, east, walk(world, 1, -1)).flows(), "斜着拐四十五度,侧向的那一份停得住");
        assertFalse(stride(world, true, east, walk(world, -1, 0)).flows(), "掉头比直角更停不住");
    }

    /** 平地上朝 (dx, dz) 走一格的平走或斜走。 */
    private static Maneuver walk(TestWorld world, int dx, int dz) {
        return route(world, new BlockPos(0, Y, 0), new BlockPos(dx, Y, dz)).get(0);
    }

    @Test
    void aStepBesideADropDoesNotRunAndTheStepBeforeItStops() {
        TestWorld ledge = new TestWorld().floor(-4, 0, 20, 0, Y - 1);
        List<Maneuver> steps = route(ledge, new BlockPos(0, Y, 0), new BlockPos(8, Y, 0));
        List<Gait.Stride> strides = strides(ledge, steps, true);
        assertTrue(strides.stream().noneMatch(Gait.Stride::sprint), "一格宽的窄道,两侧都是落坑,贴着落坑不跑");
        assertTrue(strides.stream().noneMatch(Gait.Stride::flows), "也不带着冲劲进下一步");
        // 宽地上的一步后面接窄道的一步:宽地上的这一步在落点上收脚
        TestWorld wide = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        assertFalse(stride(ledge, true, walk(wide, 1, 0), steps.get(0)).flows(), "窄道前收脚");
    }

    @Test
    void aLandingTooNarrowToStopOnIsNotRunInto() {
        // 炼药锅里只比身体宽一点点:冲进去就撞出来。落点是它的上一级不跑,前一步也不把冲劲带进来
        TestWorld world = flat().set(6, Y, 0, Blocks.CAULDRON.defaultBlockState());
        List<Maneuver> steps = route(world, new BlockPos(0, Y, 0), new BlockPos(6, Y, 0));
        List<Gait.Stride> strides = strides(world, steps, true);
        int last = steps.size() - 1;
        assertEquals(new BlockPos(6, Y, 0), steps.get(last).to());
        assertFalse(strides.get(last).sprint(), "落点里没有余量,跑不起来");
        assertFalse(strides.get(last - 1).flows(), "前一步在落点上收脚,不带冲劲进来");
        assertTrue(strides.get(0).sprint(), "前面宽敞的路照跑");
    }

    @Test
    void wadingThroughWaterNeitherSprintsNorCarries() {
        TestWorld world = flat().fill(3, Y, -8, 9, Y, 8, Blocks.WATER.defaultBlockState());
        List<Maneuver> steps = route(world, new BlockPos(0, Y, 0), new BlockPos(12, Y, 0));
        List<Gait.Stride> strides = strides(world, steps, true);
        int wet = 0;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).wading()) {
                wet++;
                assertFalse(strides.get(i).sprint(), "水里不跑");
                assertFalse(strides.get(i).flows(), "水里没有地面可借力");
            }
        }
        assertTrue(wet > 0, "路线涉过水");
    }

    @Test
    void aRunUpParkourSprintsAndTheStepsBeforeItRunToo() {
        RouteSpec spec = RouteSpec.defaults().edit().parkour(true).build();
        TestWorld world = new TestWorld().floor(-4, -4, 5, 4, Y - 1).floor(9, -4, 20, 4, Y - 1);
        List<Maneuver> steps = route(world, Fixtures.model(spec), new BlockPos(0, Y, 0), new BlockPos(12, Y, 0));
        Maneuver leap = firstOf(MoveKind.PARKOUR, steps);
        assertTrue(leap.runUp(), "四列远的跳要助跑");
        int at = steps.indexOf(leap);
        List<Gait.Stride> strides = strides(world, steps, true);
        assertTrue(strides.get(at).sprint());
        assertTrue(at > 0 && strides.get(at - 1).sprint() && strides.get(at - 1).flows(), "起跳前的步子跑着,冲劲带进跳里");
        assertFalse(strides(world, steps, false).get(at).sprint(), "饿着跑不起来");
    }
}
