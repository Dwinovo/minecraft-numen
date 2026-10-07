package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Scenes.slab;
import static com.dwinovo.numen.pathing.gametest.Scenes.stair;
import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 跑酷:越过一格、两格、三格的沟(三格要疾跑助跑);跳上高一级的落点;从下半砖、楼梯上起跳,落到下半砖上;落点是耕地时不跳;
 * 饱食度不够疾跑时不规划要疾跑的跳;空中不回身。
 *
 * <p>场景都是两块基岩台子中间一道沟:沟四格深,跳下去摔不起,只能跳过去(或绕远处的桥)。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class ParkourGameTests {

    private static final String BATCH = "pathing_parkour";

    private static final RouteSpec PARKOUR = RouteSpec.defaults().edit().parkour(true).build();

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 沟在 {@code x0..x1},台面在 y = 4,身体站在 y = 5。 */
    private static Trial gap(GameTestHelper helper, int x0, int x1) {
        Trial t = new Trial(helper).floor();
        t.fill(0, 1, 0, x0 - 1, 4, 39, Blocks.BEDROCK);
        t.fill(x1 + 1, 1, 0, 39, 4, 39, Blocks.BEDROCK);
        return t;
    }

    /** 远处 z = 30 那里一座横跨沟的桥。 */
    private static void bridge(Trial t, int x0, int x1) {
        t.fill(x0, 1, 30, x1, 4, 30, Blocks.BEDROCK);
    }

    /** 从来没在 {@code z < 25} 那一段飞过沟:没从近处跳。 */
    private static java.util.function.Consumer<Trial.Run> neverJumpedNear(Trial t, int x0, int x1) {
        return r -> {
            double x = r.body.getX() - t.origin.getX();
            double z = r.body.getZ() - t.origin.getZ();
            if (!r.body.onGround() && x > x0 && x < x1 + 1 && z < 25) {
                throw new GameTestAssertException("从近处跳过了沟:身体在 (" + x + ", " + z + ") " + r.navigation);
            }
        };
    }

    /** 一格宽的沟:跳过去,一格不改。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void jumps_a_one_block_gap(GameTestHelper helper) {
        Trial t = gap(helper, 10, 10);
        TestBody body = t.body(6, 5, 5);
        t.go(body, Goals.at(t.at(14, 5, 5)), PARKOUR).within(300).during(Scenes.noTurnInMidAir()).arrives()
                .then(Scenes::unaltered);
    }

    /** 两格宽的沟:跳过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void jumps_a_two_block_gap(GameTestHelper helper) {
        Trial t = gap(helper, 10, 11);
        TestBody body = t.body(6, 5, 5);
        t.go(body, Goals.at(t.at(14, 5, 5)), PARKOUR).within(300).during(Scenes.noTurnInMidAir()).arrives()
                .then(Scenes::unaltered);
    }

    /** 三格宽的沟:疾跑助跑,腾空时在疾跑,跳过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void sprints_across_a_three_block_gap(GameTestHelper helper) {
        Trial t = gap(helper, 10, 12);
        TestBody body = t.body(5, 5, 5);
        boolean[] sprinted = {false};
        t.go(body, Goals.at(t.at(16, 5, 5)), PARKOUR).within(300).during(Scenes.noTurnInMidAir())
                .during(r -> {
                    double x = r.body.getX() - t.origin.getX();
                    if (!r.body.onGround() && x > 10 && x < 13 && r.body.isSprinting()) {
                        sprinted[0] = true;
                    }
                })
                .arrives().then(r -> {
                    if (!sprinted[0]) {
                        throw new GameTestAssertException("三格的沟不是疾跑着跳过去的");
                    }
                });
    }

    /** 两格宽的沟,对岸高一级:疾跑着跳上去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void jumps_up_to_a_higher_landing(GameTestHelper helper) {
        Trial t = gap(helper, 10, 11);
        t.fill(12, 5, 0, 39, 5, 39, Blocks.BEDROCK);
        TestBody body = t.body(5, 5, 5);
        t.go(body, Goals.at(t.at(14, 6, 5)), PARKOUR).within(300).during(Scenes.noTurnInMidAir()).arrives()
                .then(Scenes::unaltered);
    }

    /** 沟边上一块下半砖、一级楼梯,各从上面起跳过两格的沟;另一道上对岸落点是一块下半砖:都跳得过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void takes_off_from_slabs_and_stairs_and_lands_on_a_slab(GameTestHelper helper) {
        Trial t = gap(helper, 10, 11);
        t.fill(0, 5, 10, 39, 6, 10, Blocks.BEDROCK);
        t.fill(0, 5, 20, 39, 6, 20, Blocks.BEDROCK);
        t.set(9, 5, 5, slab(SlabType.BOTTOM));
        t.set(9, 5, 15, stair(Direction.EAST));
        t.set(12, 5, 25, slab(SlabType.BOTTOM));
        TestBody fromSlab = t.body(6, 5, 5);
        t.go(fromSlab, Goals.at(t.at(14, 5, 5)), PARKOUR).within(400).during(Scenes.noTurnInMidAir()).arrives();
        TestBody fromStair = t.body(6, 5, 15);
        t.go(fromStair, Goals.at(t.at(14, 5, 15)), PARKOUR).within(400).during(Scenes.noTurnInMidAir()).arrives();
        TestBody ontoSlab = t.body(6, 5, 25);
        t.go(ontoSlab, Goals.at(t.at(14, 5, 25)), PARKOUR).within(400).during(Scenes.noTurnInMidAir()).arrives();
    }

    /**
     * 两格宽的沟,这一岸沿沟一排是土径、对岸沿沟一排是耕地(规格许走耕地),两排顶面一样高:从土径跳到耕地上会把耕地踩坏,
     * 不跳,绕远处的桥走过去,耕地一块没坏。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void does_not_jump_onto_farmland(GameTestHelper helper) {
        Trial t = gap(helper, 10, 11);
        t.fill(9, 4, 0, 9, 4, 39, Blocks.DIRT_PATH);
        // 湿透的耕地:随机刻里要干上七回才变回泥土,用例这点工夫干不透
        t.fill(12, 4, 0, 12, 4, 39, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE));
        bridge(t, 10, 11);
        TestBody body = t.body(6, 5, 5);
        t.go(body, Goals.at(t.at(14, 5, 5)), PARKOUR.edit().allow(Kind.FRAGILE).build()).within(600)
                .during(neverJumpedNear(t, 10, 11)).arrives();
    }

    /** 饱食度一直只有 6(原版疾跑要高于 6;和平模式会自己回饱食度,用例每刻压回去):三格宽的沟要疾跑才跳得过,不规划这一跳,绕远处的桥。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void does_not_plan_sprint_jumps_when_hungry(GameTestHelper helper) {
        Trial t = gap(helper, 10, 12);
        bridge(t, 10, 12);
        TestBody body = t.body(6, 5, 5);
        t.go(body, Goals.at(t.at(15, 5, 5)), PARKOUR).within(600)
                .during(r -> r.body.getFoodData().setFoodLevel(6))
                .during(neverJumpedNear(t, 10, 12)).arrives();
    }
}
