package com.dwinovo.numen.plugins.minecraft.pathing.gametest;

import static com.dwinovo.numen.plugins.minecraft.pathing.gametest.Trial.ARENA;


import com.dwinovo.numen.plugins.minecraft.pathing.search.Goals;
import com.dwinovo.numen.plugins.minecraft.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 目标:用一格方块(狭窄矿道里的熔炉)、梯子上与水里的一格;起点在贵成员里;最后一步进目标的同一刻搜索失败;垫柱到目标格站稳
 * 才报到;规划之后视线被挡住。
 */
public class GoalGameTests {

    private static final String BATCH = "pathing_goals";
    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().changes(true).consent(false).build();

    /** 身体脚下的那一格(相对场地)。 */
    private static BlockPos feet(Trial t, Trial.Run r) {
        return r.body.blockPosition().subtract(t.origin);
    }

    private static void at(Trial t, Trial.Run r, int x, int y, int z) {
        if (!feet(t, r).equals(new BlockPos(x, y, z))) {
            throw new GameTestAssertException("应当停在 (" + x + "," + y + "," + z + "),却在 " + t.rel(r.body.blockPosition()));
        }
    }

    /**
     * 路过一格:走进那一格就算到了,身体不停稳——到达的那一刻还带着走路的速度;同样的路停在那一格时,到达的那一刻身体已经停住。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 318)
    public static void passing_through_a_cell_does_not_stop_in_it(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody passing = t.body(2, 1, 5);
        t.go(passing, com.dwinovo.numen.plugins.minecraft.pathing.api.NavRequest.to(Goals.at(t.at(14, 1, 5)), RouteSpec.defaults())
                .passing()).within(300).arrives().then(r -> {
                    at(t, r, 14, 1, 5);
                    if (r.body.getDeltaMovement().horizontalDistance() < 0.05) {
                        throw new GameTestAssertException("路过却停住了:" + r.body.getDeltaMovement());
                    }
                });
        TestBody stopping = t.body(2, 1, 20);
        t.go(stopping, Goals.at(t.at(14, 1, 20)), RouteSpec.defaults()).within(300).arrives().then(r -> {
            at(t, r, 14, 1, 20);
            if (r.body.getDeltaMovement().horizontalDistance() >= 0.05) {
                throw new GameTestAssertException("停在那一格却还在走:" + r.body.getDeltaMovement());
            }
        });
    }

    /** 去处在头顶三格高的半空,身上有圆石:垫柱上去,停稳在那一格上才报到。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 261)
    public static void arrives_only_once_settled_on_its_own_pillar(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(5, 1, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(5, 4, 5)), NATURAL).within(251).arrives().then(r -> settled(t, r, 4));
    }

    /** 报到的那一刻站在地上、脚正好在那一层、不再往上下窜,水平也停住了。 */
    private static void settled(Trial t, Trial.Run r, int level) {
        double feetY = r.body.getY() - t.origin.getY();
        var v = r.body.getDeltaMovement();
        if (!r.body.onGround() || Math.abs(feetY - level) > 1.0E-3 || Math.abs(v.y) > 0.1
                || Math.sqrt(v.x * v.x + v.z * v.z) > 0.1) {
            throw new GameTestAssertException("还没站稳就报到了:脚高 " + feetY + " 着地 " + r.body.onGround() + " 速度 " + v);
        }
    }

    /** 某一格可以是梯子上的一格:爬到半截停住,脚就在那一格,挂在梯子上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 294)
    public static void arrives_at_a_cell_on_a_ladder(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(9, 1, 3, 12, 6, 7, Blocks.STONE);
        for (int y = 1; y <= 6; y++) {
            t.set(8, y, 5, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
        }
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(8, 4, 5)), RouteSpec.defaults()).within(284).arrives().then(r -> {
            at(t, r, 8, 4, 5);
            if (!r.body.onClimbable()) {
                throw new GameTestAssertException("应当挂在梯子上");
            }
        });
    }

    /** 某一格可以是水里的一格:池子三格深,去处是水面那一层,浮在那儿。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 303)
    public static void arrives_at_a_cell_in_water(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        UpDownGameTests.pool(t, 8, 3, 13, 8, 3);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(10, 0, 5)), RouteSpec.defaults()).within(293).arrives().then(r -> {
            if (!r.body.isInWater()) {
                throw new GameTestAssertException("应当在水里:" + t.rel(r.body.blockPosition()));
            }
        });
    }
}
