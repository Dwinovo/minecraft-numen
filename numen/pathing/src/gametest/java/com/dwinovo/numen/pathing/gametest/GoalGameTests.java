package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import java.util.List;

import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.api.entity.Sight;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.phys.BlockHitResult;
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
        t.go(passing, com.dwinovo.numen.pathing.api.NavRequest.to(Goals.at(t.at(14, 1, 5)), RouteSpec.defaults())
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

    /** 此刻从身体的眼睛用得上 {@code target} 的某一面(第 0 层 {@link Sight#use}),视线上什么也不隔着;交出那一次视线。 */
    private static Sight.Trace sees(Trial.Run r, BlockPos target) {
        for (Direction face : Direction.values()) {
            Sight.Trace seen = Sight.use(r.body.level(), r.body.getEyePosition(), r.body.blockInteractionRange(), target,
                    face);
            if (seen != null && seen.clear(face)) {
                return seen;
            }
        }
        throw new GameTestAssertException("从停下的地方看不清它:" + r.body.position());
    }

    /**
     * 狭窄矿道(一格宽两格高)里,熔炉嵌在南边那条矿道的北壁上,只有朝矿道的南面敞开;身体在北边那条平行矿道里,隔着一层石头,
     * 几何上够得着它却看不见。用它:绕到南边矿道、站到正对开口的一侧,看得见,打得开。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 315)
    public static void uses_a_furnace_in_a_narrow_tunnel_from_its_open_side(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 0, 18, 3, 7, Blocks.STONE);
        t.fill(4, 1, 2, 16, 2, 2, Blocks.AIR);
        t.fill(4, 1, 5, 16, 2, 5, Blocks.AIR);
        t.fill(4, 1, 2, 4, 2, 5, Blocks.AIR);
        t.set(10, 1, 4, Blocks.FURNACE);
        BlockPos furnace = t.at(10, 1, 4);
        TestBody body = t.body(10, 1, 2);
        Goals.Use use = Goals.use(t.level, Snapshots.of(body).stats(), furnace);
        if (!use.open().equals(List.of(Direction.SOUTH))) {
            throw new GameTestAssertException("只该有南面敞开:" + use.open());
        }
        t.go(body, use, RouteSpec.defaults()).within(500).arrives().then(r -> {
            if (feet(t, r).getZ() != 5) {
                throw new GameTestAssertException("应当站在南边那条矿道里:" + t.rel(r.body.blockPosition()));
            }
            Sight.Trace seen = sees(r, furnace);
            r.body.look().at(seen.point());
            BlockHitResult hit = r.body.mouse().on(furnace);
            if (hit == null || !r.body.gameMode.useItemOn(r.body, t.level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                    hit).consumesAction() || !(r.body.containerMenu instanceof FurnaceMenu)) {
                throw new GameTestAssertException("打不开熔炉:" + hit + " " + r.body.containerMenu);
            }
            r.body.closeContainer();
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
        t.go(body, Goals.at(t.at(8, 4, 5)), RouteSpec.defaults()).within(400).arrives().then(r -> {
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
        t.go(body, Goals.at(t.at(10, 0, 5)), RouteSpec.defaults()).within(400).arrives().then(r -> {
            if (!r.body.isInWater()) {
                throw new GameTestAssertException("应当在水里:" + t.rel(r.body.blockPosition()));
            }
        });
    }
}
