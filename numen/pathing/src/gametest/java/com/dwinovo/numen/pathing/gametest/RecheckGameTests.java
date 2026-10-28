package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.PlanQuery;
import com.dwinovo.numen.pathing.drive.Blockage;
import com.dwinovo.numen.pathing.drive.Journal;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
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

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 执行复核:规划之后、执行之前世界变了,每一步开始前在活世界上用同一个前提往前核接下来几步,不成立就停下,结局点出哪一格、什么方块、
 * 哪一条前提;走在路上前方几步的格被改了,走到跟前之前就停下重规划;规划看不见的东西让身体做不到那一步时,同一步几次走不下去就收场,
 * 不无休止地重搜同一条路。
 */
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
     * 先规划出穿过走廊的路,再往走廊正中放一块石头,然后照规划的那条路走:走到石头跟前那一步复核不成立,这一步记为不通,从那里
     * 重搜,别处没有路。没路是因为规格不许挖——诊断点出许了挖就有路,要挖的正是那块石头("缺什么"比"受阻"更说明问题)。世界除了那块
     * 石头一格不变。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 261)
    public static void stops_where_the_world_changed_after_planning(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        corridor(t);
        TestBody body = t.body(4, 1, 5);
        BlockPos stone = t.at(12, 1, 5);
        NavRequest request = NavRequest.to(Goals.at(t.at(19, 1, 5)), RouteSpec.defaults());
        t.plan(body, PlanQuery.of(request.goal(), request.spec()), plan -> {
            if (plan.route() == null) {
                throw new GameTestAssertException("没规划出路:" + plan.outcome());
            }
            t.change(12, 1, 5, Blocks.STONE.defaultBlockState());
            t.go(body, request.following(plan.route())).within(400)
                    .fails(Outcome.NeedsChanges.class, o -> {
                        if (o.changes().size() != 1 || !o.changes().get(0).pos().equals(stone)
                                || !(o.changes().get(0) instanceof Edit.Dig dig) || !dig.state().is(Blocks.STONE)) {
                            throw new GameTestAssertException("结局没点对:" + o.changes());
                        }
                    })
                    .then(Scenes::unaltered);
        });
    }

    /**
     * 走廊正中停着一条船(规划只看方块,看不见它;船比走廊宽,被两边的墙卡住推不走,又矮得身体踩上去就顶头):那一步走不
     * 过去,这一步记为不通,重搜没有别的路,收场。结局是"受阻",点出船所在的那一格(她碰上船时把它往前顶了一点,
     * 以船最后停的那一格为准)、平走、"卡住"。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 489)
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

    /**
     * 平地上一条朝 +x 的直路,走过 x = 10 时把路线上往后第 3 步的落点 {@code change} 掉:她因往前核不成立停下、从脚下重规划,
     * 最后照样到达。往前核不成立的那一刻,身体还没走到改掉的那一步的起点。
     */
    private static Trial.Run changesAhead(GameTestHelper helper, BiConsumer<Trial, BlockPos> change) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(4, 1, 5);
        Maneuver[] changed = {null};
        boolean[] noticed = {false};
        return t.go(body, Goals.at(t.at(30, 1, 5)), RouteSpec.defaults()).within(600)
                .during(r -> {
                    if (changed[0] == null) {
                        List<Route.Leg> legs = r.navigation.remaining();
                        if (r.body.getX() - t.origin.getX() >= 10 && legs.size() > 3) {
                            changed[0] = legs.get(3).maneuver();
                            change.accept(t, changed[0].to().subtract(t.origin));
                        }
                        return;
                    }
                    if (!noticed[0] && r.navigation.report().journal().replans(Journal.Replan.AHEAD) > 0) {
                        noticed[0] = true;
                        if (r.body.getX() >= changed[0].from().getX()) {
                            throw new GameTestAssertException("走到了改掉的那一步 " + t.rel(changed[0].from()) + "→" + t.rel(changed[0].to())
                                    + " 的起点才停下,身体在 " + t.rel(r.body.blockPosition()));
                        }
                    }
                })
                .arrives().then(r -> {
                    if (changed[0] == null) {
                        throw new GameTestAssertException("场景没起作用:没在路上改到格子");
                    }
                    if (r.report.journal().replans(Journal.Replan.AHEAD) < 1) {
                        throw new GameTestAssertException("没有因往前核不成立重规划:" + r.report.journal().replans());
                    }
                });
    }

    /** 前方第 3 步的落点被砌上两格高的石头:那一步不成立,她没走到那一步的起点就停下。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void replans_before_a_block_placed_three_steps_ahead(GameTestHelper helper) {
        changesAhead(helper, (t, at) -> {
            t.change(at.getX(), at.getY(), at.getZ(), Blocks.STONE.defaultBlockState());
            t.change(at.getX(), at.getY() + 1, at.getZ(), Blocks.STONE.defaultBlockState());
        });
    }

    /** 前方第 3 步落点脚下的地板被挖开:那一步不成立,她没走到那一步的起点就停下。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void replans_before_the_floor_dug_out_three_steps_ahead(GameTestHelper helper) {
        changesAhead(helper, (t, at) -> t.change(at.getX(), at.getY() - 1, at.getZ(), Blocks.AIR.defaultBlockState()));
    }

    /**
     * 前方第 3 步落点脚下的那块地板换成一格水(像挖开了倒进一桶水):落点托不住身体,那一步不成立,她没走到那一步的起点就停下。
     * 水往地板底下流开是原版自己流的,对账时不算。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void replans_before_water_poured_three_steps_ahead(GameTestHelper helper) {
        changesAhead(helper, (t, at) -> t.change(at.getX(), at.getY() - 1, at.getZ(), Blocks.WATER.defaultBlockState()))
                .passive((before, now) -> now.getFluidState().is(net.minecraft.tags.FluidTags.WATER));
    }
}
