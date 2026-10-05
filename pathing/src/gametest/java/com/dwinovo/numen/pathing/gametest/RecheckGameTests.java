package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.PlanQuery;
import com.dwinovo.numen.pathing.drive.Blockage;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Reason;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 执行复核:规划之后、执行之前世界变了,那一步开始前在活世界上用同一个前提复核,不成立就停下,结局点出哪一格、什么方块、
 * 哪一条前提;规划看不见的东西让身体做不到那一步时,同一步几次走不下去就收场,不无休止地重搜同一条路。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class RecheckGameTests {

    private static final String BATCH = "pathing_recheck";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 一条基岩封着的走廊,一格宽、两格高,从 x = 3 到 x = 20,在 z = 5 那一排。 */
    private static void corridor(Trial t) {
        t.fill(2, 0, 4, 21, 3, 6, Blocks.BEDROCK);
        t.fill(3, 1, 5, 20, 2, 5, Blocks.AIR);
    }

    /**
     * 先规划出穿过走廊的路,再往走廊正中放一块石头,然后照规划的那条路走:走到石头跟前那一步复核不成立,停下;别处没有路,
     * 结局是"受阻",点出那一格、那块石头、平走,前提是"要改地形而规格不许"。世界除了那块石头一格不变。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void stops_where_the_world_changed_after_planning(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        corridor(t);
        TestBody body = t.body(4, 1, 5);
        BlockPos stone = t.at(12, 1, 5);
        NavRequest request = NavRequest.to(Goals.at(t.at(19, 1, 5)), RouteSpec.defaults());
        t.plan(body, PlanQuery.of(request.goal(), request.spec(), 1), plan -> {
            if (plan.candidates().isEmpty()) {
                throw new GameTestAssertException("没规划出路:" + plan.outcome());
            }
            t.change(12, 1, 5, Blocks.STONE.defaultBlockState());
            t.go(body, request.following(plan.candidates().get(0).route())).within(400)
                    .fails(Outcome.Blocked.class, o -> {
                        Blockage b = o.blockage();
                        if (!b.cell().equals(stone) || !b.block().is(Blocks.STONE) || b.move() != MoveKind.WALK
                                || b.reason() != Reason.NO_DIGGING || b.hitch() != null) {
                            throw new GameTestAssertException("结局没点对:" + b);
                        }
                    })
                    .then(Scenes::unaltered);
        });
    }

    /**
     * 走廊正中停着一条船(规划只看方块,看不见它;船比走廊宽,被两边的墙卡住推不走,又矮得身体踩上去就顶头):那一步走不
     * 过去,重搜还是同一条路,同一步三次走不下去就收场。结局是"受阻",点出船所在的那一格(她碰上船时把它往前顶了一点,
     * 以船最后停的那一格为准)、平走、"卡住"。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 900)
    public static void gives_up_after_a_few_tries_when_the_body_cannot_do_the_step(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        corridor(t);
        BlockPos cell = t.at(12, 1, 5);
        Boat boat = EntityType.BOAT.create(t.level);
        boat.moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 90, 0);
        t.level.addFreshEntity(boat);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(19, 1, 5)), RouteSpec.defaults()).within(800)
                .fails(Outcome.Blocked.class, o -> {
                    Blockage b = o.blockage();
                    if (!b.cell().equals(boat.blockPosition()) || b.move() != MoveKind.WALK || b.hitch() != Blockage.Hitch.STUCK) {
                        throw new GameTestAssertException("结局没点对:" + b);
                    }
                })
                .then(r -> boat.discard());
    }
}
