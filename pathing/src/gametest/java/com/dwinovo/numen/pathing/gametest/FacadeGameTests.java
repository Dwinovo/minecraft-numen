package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import java.util.List;

import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.PlanQuery;
import com.dwinovo.numen.pathing.api.PlanResult;
import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.pathing.body.Controls;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 门面:叫停时松开潜行、交出实际账;先规划再按挑中的候选走,走的就是那条;只搜不走时身体不动、世界不变。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class FacadeGameTests {

    private static final String BATCH = "pathing_facade";
    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().changes(true).consent(false).build();

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /**
     * 两块基岩台子之间一道四格宽的沟,身上有圆石:探出台边潜行着搭桥,桥搭到第二块时叫停——潜行键当场松开(身体下一刻的物理
     * 步进里站起来),叫停交出的实际账里正是已经放下的那几块,与世界一致。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void stopping_releases_sneak_and_hands_over_the_ledger(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(0, 1, 0, 9, 4, 39, Blocks.BEDROCK);
        t.fill(14, 1, 0, 39, 4, 39, Blocks.BEDROCK);
        TestBody body = t.body(6, 5, 20);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        Report[] handed = {null};
        t.go(body, Goals.at(t.at(17, 5, 20)), NATURAL).within(700)
                .during(r -> {
                    if (handed[0] == null && r.body.isShiftKeyDown()
                            && r.navigation.report().bill().places().size() >= 2) {
                        handed[0] = r.navigation.stop();
                    }
                })
                .stops().then(r -> {
                    if (r.body.controls().held(Controls.Key.SNEAK)) {
                        throw new GameTestAssertException("叫停之后还按着潜行");
                    }
                    List<BlockPos> placed = handed[0].bill().places();
                    if (placed.size() < 2 || !placed.equals(r.report.bill().places())) {
                        throw new GameTestAssertException("叫停交出的账不对:" + placed + " / " + r.report.bill().places());
                    }
                });
    }

    /**
     * 一道基岩墙上两个口,近的在 z = 8,远的在 z = 22:先规划几条候选,挑穿远处那个口的一条交给导航——身体就从远处那个口
     * 穿过去,一格不改。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void drives_the_candidate_it_picked(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 0, 10, 3, 39, Blocks.BEDROCK);
        t.fill(10, 1, 8, 10, 2, 8, Blocks.AIR);
        t.fill(10, 1, 22, 10, 2, 22, Blocks.AIR);
        TestBody body = t.body(5, 1, 12);
        BlockPos far = t.at(10, 1, 22);
        NavRequest request = NavRequest.to(Goals.at(t.at(15, 1, 12)), RouteSpec.defaults());
        t.plan(body, PlanQuery.of(request.goal(), request.spec(), 3), plan -> {
            Route picked = plan.candidates().stream().map(PlanResult.Candidate::route)
                    .filter(route -> route.nodes().contains(far)).findFirst()
                    .orElseThrow(() -> new GameTestAssertException("候选里没有穿远处那个口的:" + plan.candidates().size()));
            if (plan.candidates().get(0).route() == picked) {
                throw new GameTestAssertException("最便宜的那条就穿远处的口,场景没起作用");
            }
            int[] crossedAt = {Integer.MIN_VALUE};
            t.go(body, request.following(picked)).within(700)
                    .during(r -> {
                        BlockPos at = r.body.blockPosition();
                        if (at.getX() == far.getX() && crossedAt[0] == Integer.MIN_VALUE) {
                            crossedAt[0] = at.getZ();
                        }
                    })
                    .arrives().then(Scenes::unaltered).then(r -> {
                        if (crossedAt[0] != far.getZ()) {
                            throw new GameTestAssertException("没从挑中的那个口穿过去:" + (crossedAt[0] - t.origin.getZ()));
                        }
                    });
        });
    }

    /**
     * 一道泥土墙横贯场地,许挖许放,手上拿着剑、背包里有锹:只搜不走——规划出挖墙的候选,这期间身体一动不动,手上还是
     * 那把剑,世界一格不变。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 300)
    public static void planning_moves_nothing(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 0, 10, 3, 39, Blocks.DIRT);
        TestBody body = t.body(5, 1, 12);
        body.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        body.getInventory().setItem(20, new ItemStack(Items.IRON_SHOVEL));
        Vec3 at = body.position();
        t.plan(body, PlanQuery.of(Goals.at(t.at(15, 1, 12)), NATURAL, 3), plan -> {
            if (plan.candidates().isEmpty() || plan.candidates().get(0).bill().digs().isEmpty()) {
                throw new GameTestAssertException("应当规划出挖墙的路:" + plan.outcome());
            }
            if (!body.position().equals(at)) {
                throw new GameTestAssertException("只搜不走,身体却从 " + at + " 挪到了 " + body.position());
            }
            if (!body.getMainHandItem().is(Items.IRON_SWORD) || !body.getInventory().getItem(20).is(Items.IRON_SHOVEL)) {
                throw new GameTestAssertException("只搜不走,手上或背包却变了");
            }
            t.untouched();
            body.leave();
            helper.succeed();
        });
    }
}
