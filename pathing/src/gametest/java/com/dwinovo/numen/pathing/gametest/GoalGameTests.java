package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Searches;
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
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 目标:贴脸、站上、靠近、环形站位、到某一高度、远离一组生物、多个目标取其一;起点在贵成员里;最后一步进目标的同一刻搜索失败;
 * 垫柱或搭桥到目标格站稳才报到;够得着没视线;不垫柱站到目标旁上方;可站却到不了;给的高度在半空或方块里时落到那一列的地面。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class GoalGameTests {

    private static final String BATCH = "pathing_goals";
    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build();

    /** 要拦住搜索线程池的用例单独一批,不挡着别的用例的搜索。 */
    private static final String HELD_BATCH = "pathing_goals_held";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    @BeforeBatch(batch = HELD_BATCH)
    public static void settleHeld(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 身体脚下的那一格(相对场地)。 */
    private static BlockPos feet(Trial t, Trial.Run r) {
        return r.body.blockPosition().subtract(t.origin);
    }

    private static void at(Trial t, Trial.Run r, int x, int y, int z) {
        if (!feet(t, r).equals(new BlockPos(x, y, z))) {
            throw new GameTestAssertException("应当停在 (" + x + "," + y + "," + z + "),却在 " + t.rel(r.body.blockPosition()));
        }
    }

    /** 贴脸:走到手够得着那一块、看得见它的地方。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void reaches_a_block(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(14, 1, 5, Blocks.CRAFTING_TABLE);
        TestBody body = t.body(4, 1, 5);
        BlockPos table = t.at(14, 1, 5);
        t.go(body, Goals.reach(table, Snapshots.of(body).stats()), RouteSpec.defaults()).within(300).arrives()
                .then(r -> {
                    double distance = Math.sqrt(new AABB(table).distanceToSqr(r.body.getEyePosition()));
                    if (distance >= r.body.blockInteractionRange()) {
                        throw new GameTestAssertException("够不着:" + distance);
                    }
                });
    }

    /** 站上:站到那一块上面,托着脚的就是它。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void stands_on_a_block(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(12, 1, 5, Blocks.OAK_PLANKS);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.standOn(t.at(12, 1, 5)), RouteSpec.defaults()).within(300).arrives()
                .then(r -> at(t, r, 12, 2, 5));
    }

    /** 靠近:停在离那一点三格以内,不必走到跟前。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void comes_near(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(4, 1, 5);
        BlockPos point = t.at(20, 1, 5);
        t.go(body, Goals.near(point, 3), RouteSpec.defaults()).within(300).arrives().then(r -> {
            double d = Math.sqrt(r.body.blockPosition().distSqr(point));
            if (d > 3 || d < 2) {
                throw new GameTestAssertException("应当刚好停进三格以内:" + d);
            }
        });
    }

    /** 环形站位:停在离那一列水平三到五格之间,不走进内圈。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void stands_in_a_ring(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(20, 1, 20);
        BlockPos center = t.at(21, 1, 21);
        t.go(body, Goals.ring(center, 3, 5), RouteSpec.defaults()).within(300).arrives().then(r -> {
            double dx = r.body.getBlockX() - center.getX();
            double dz = r.body.getBlockZ() - center.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 3 || d > 5) {
                throw new GameTestAssertException("没站在环上:" + d);
            }
        });
    }

    /** 到某一高度:顺着一道台阶上到第六层。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void climbs_to_a_level(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        for (int i = 0; i < 5; i++) {
            t.fill(8 + i, 1, 4, 20, 1 + i, 6, Blocks.STONE);
        }
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.level(t.origin.getY() + 6), RouteSpec.defaults()).within(400).arrives().then(r -> {
            if (feet(t, r).getY() != 6) {
                throw new GameTestAssertException("没到第六层:" + t.rel(r.body.blockPosition()));
            }
        });
    }

    /** 远离两只怪:身体在它们的危险半径里,走到离每一只都够远的地方。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void gets_away_from_threats(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(20, 1, 20);
        List<Threat> threats = List.of(new Threat(t.origin.getX() + 18.5, t.origin.getY(), t.origin.getZ() + 20.5, 6),
                new Threat(t.origin.getX() + 22.5, t.origin.getY(), t.origin.getZ() + 20.5, 6));
        t.go(body, Goals.awayFrom(threats), RouteSpec.defaults()).within(300).arrives().then(r -> {
            for (Threat th : threats) {
                double dx = r.body.getX() - th.x();
                double dz = r.body.getZ() - th.z();
                if (Math.sqrt(dx * dx + dz * dz) < th.radius() - 0.5) {
                    throw new GameTestAssertException("还在危险半径里:" + th);
                }
            }
        });
    }

    /** 多个目标取其一:两处去处一近一远,去近的那处。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void picks_the_nearer_of_several_goals(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(10, 1, 10);
        Goal goal = Goals.anyOf(List.of(Goals.at(t.at(30, 1, 10)), Goals.at(t.at(10, 1, 16))));
        t.go(body, goal, RouteSpec.defaults()).within(300).arrives().then(r -> at(t, r, 10, 1, 16));
    }

    /** 起点就在一个成员里,但停在那儿要付很大的到达价;十格外另一个成员不收:走过去,不在原地算到。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void leaves_an_expensive_member_for_a_cheap_one(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(10, 1, 10);
        Goal goal = Goals.anyOf(List.of(Goals.priced(Goals.near(t.at(10, 1, 10), 2), 500),
                Goals.at(t.at(20, 1, 10))));
        t.go(body, goal, RouteSpec.defaults()).within(300).arrives().then(r -> at(t, r, 20, 1, 10));
    }

    /**
     * 去处是一片压力板(出厂规格排除机关格,搜索永远走不进去),身体被一下推着滑进了那一片;推的那一刻派出的搜索要很久才说
     * 没路:它说没路的那一刻她已经停在去处里了,报到达。压力板被她踩下去是原版自己的事,不算进账。
     *
     * <p>"很久"不靠搜索慢:派搜索之前先让搜索的线程池每个线程都等着放行,推的那一刻派出的搜索排在它们后面;她停住了才放行。
     * 放行最多等十秒,用例失败也不会一直占着线程池。
     */
    @GameTest(template = ARENA, batch = HELD_BATCH, timeoutTicks = 600)
    public static void arrives_when_a_failing_search_returns_after_it_slid_in(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 3, 14, 1, 7, Blocks.STONE_PRESSURE_PLATE);
        TestBody body = t.body(8, 1, 5);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            Searches.submit(cancelled -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        }
        boolean[] pushed = {false};
        t.go(body, Goals.near(t.at(12, 1, 5), 2), RouteSpec.defaults()).within(500)
                .passive((before, now) -> before.is(Blocks.STONE_PRESSURE_PLATE) && now.is(Blocks.STONE_PRESSURE_PLATE))
                .during(r -> {
                    if (!pushed[0]) {
                        pushed[0] = true;
                        r.body.setDeltaMovement(0.9, 0, 0);
                        r.body.hurtMarked = true;
                    } else if (r.body.onGround() && r.body.getDeltaMovement().horizontalDistanceSqr() < 1.0E-6) {
                        release.countDown();
                    }
                })
                .arrives();
    }

    /** 去处在头顶三格高的半空,身上有圆石:垫柱上去,停稳在那一格上才报到。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void arrives_only_once_settled_on_its_own_pillar(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(5, 1, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(5, 4, 5)), NATURAL).within(400).arrives().then(r -> settled(t, r, 4));
    }

    /** 去处在一道沟上方的半空(与两岸台面齐平),身上有圆石:搭桥过去,停稳在最后垫的那一块上才报到。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void arrives_only_once_settled_on_its_own_bridge(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(0, 1, 0, 9, 4, 39, Blocks.BEDROCK);
        t.fill(14, 1, 0, 39, 4, 39, Blocks.BEDROCK);
        TestBody body = t.body(6, 5, 20);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(12, 5, 20)), NATURAL).within(400).arrives().then(r -> settled(t, r, 5));
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

    /** 要够的那一块四面与顶上罩着玻璃:走到够得着的地方,但看不见它,不报到达,结局是"看不见"。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void reach_without_sight_is_not_arrival(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(9, 1, 4, 11, 2, 6, Blocks.GLASS);
        t.set(10, 1, 5, Blocks.CRAFTING_TABLE);
        BlockPos table = t.at(10, 1, 5);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.reach(table, Snapshots.of(body).stats()), RouteSpec.defaults()).within(300)
                .fails(Outcome.NoLineOfSight.class, o -> {
                    if (!o.target().equals(table)) {
                        throw new GameTestAssertException("看不见的应当是那张工作台:" + o);
                    }
                });
    }

    /** 要够的那一块摆在三格高的石柱顶上,身上有圆石、许改地形:站在地上就够得着,不在旁边垫柱爬上去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void does_not_pillar_up_beside_the_target(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 5, 10, 2, 5, Blocks.STONE);
        t.set(10, 3, 5, Blocks.CRAFTING_TABLE);
        BlockPos table = t.at(10, 3, 5);
        TestBody body = t.body(4, 1, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.reach(table, Snapshots.of(body).stats()), NATURAL).within(300).arrives()
                .then(Scenes::unaltered);
    }

    /**
     * 身体关在一间基岩屋子里,去处那一格站得住,却封在屋墙里的一个基岩小格中:走不到,结局是"搜完无路",世界一格不变。
     * 屋子是基岩的,诊断放宽到许改地形、有料也出不去,很快就搜完。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void reports_a_standable_goal_it_cannot_reach(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 0, 2, 17, 4, 8, Blocks.BEDROCK);
        t.fill(3, 1, 3, 11, 3, 7, Blocks.AIR);
        t.fill(15, 1, 5, 15, 2, 5, Blocks.AIR);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(15, 1, 5)), RouteSpec.defaults()).within(300).fails(Outcome.NoRoute.class)
                .then(Scenes::unaltered);
    }

    /** 给的高度一个在半空、一个埋在地板里:都落到那一列的地面上去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void a_goal_in_the_air_or_in_the_ground_lands_on_the_ground(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody high = t.body(4, 1, 5);
        TestBody low = t.body(4, 1, 15);
        var stats = Snapshots.of(high).stats();
        t.go(high, Goals.ground(t.level, stats, t.at(12, 8, 5)), RouteSpec.defaults()).within(300).arrives()
                .then(r -> at(t, r, 12, 1, 5));
        t.go(low, Goals.ground(t.level, stats, t.at(12, 0, 15)), RouteSpec.defaults()).within(300).arrives()
                .then(r -> at(t, r, 12, 1, 15));
    }
}
