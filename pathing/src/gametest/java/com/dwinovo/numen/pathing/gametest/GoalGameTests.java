package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.pathing.body.Crosshair;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Searches;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Reach;
import com.dwinovo.numen.pathing.world.Sight;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 目标:用一格方块(狭窄矿道里的熔炉、只有一面敞开的箱子、悬崖上的工作台、隔着高草)、站上、靠近、环形站位、到某一高度、
 * 梯子上与水里的一格、远离一组生物、多个目标取其一;起点在贵成员里;最后一步进目标的同一刻搜索失败;垫柱或搭桥到目标格站稳
 * 才报到;规划之后视线被挡住;不垫柱站到目标旁上方;可站却到不了。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class GoalGameTests {

    private static final String BATCH = "pathing_goals";
    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().changes(true).consent(false).build();

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

    /**
     * 路过一格:走进那一格就算到了,身体不停稳——到达的那一刻还带着走路的速度;同样的路停在那一格时,到达的那一刻身体已经停住。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
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

    /** 用:走到看得见它某一面、点得到它的地方。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void uses_a_block(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(14, 1, 5, Blocks.CRAFTING_TABLE);
        TestBody body = t.body(4, 1, 5);
        BlockPos table = t.at(14, 1, 5);
        t.go(body, Goals.use(t.level, Snapshots.of(body).stats(), table), RouteSpec.defaults()).within(300).arrives()
                .then(r -> sees(r, table));
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
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
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
            Aim.look(r.body, seen.point());
            BlockHitResult hit = Crosshair.on(r.body, furnace);
            if (hit == null || !r.body.gameMode.useItemOn(r.body, t.level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                    hit).consumesAction() || !(r.body.containerMenu instanceof FurnaceMenu)) {
                throw new GameTestAssertException("打不开熔炉:" + hit + " " + r.body.containerMenu);
            }
            r.body.closeContainer();
        });
    }

    /** 箱子三面与顶上罩着(顶上是玻璃),只有西面敞开;身体在东面:绕到西面去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void uses_a_chest_from_its_only_open_side(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(12, 1, 5, Blocks.CHEST);
        t.set(12, 1, 4, Blocks.STONE).set(12, 1, 6, Blocks.STONE).set(13, 1, 5, Blocks.STONE);
        t.set(12, 2, 5, Blocks.GLASS);
        BlockPos chest = t.at(12, 1, 5);
        TestBody body = t.body(17, 1, 5);
        t.go(body, Goals.use(t.level, Snapshots.of(body).stats(), chest), RouteSpec.defaults()).within(400).arrives()
                .then(r -> {
                    if (feet(t, r).getX() >= 12 || sees(r, chest).face() != Direction.WEST) {
                        throw new GameTestAssertException("应当在西面用它:" + t.rel(r.body.blockPosition()));
                    }
                });
    }

    /**
     * 工作台在三格高的悬崖上、离崖边三格;身体在崖脚,几何上够得着它,中间却隔着崖壁的石头。旁边有一道台阶能上去:不停在崖底,
     * 上到崖顶看得见它的地方。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void uses_a_table_on_a_cliff_from_the_top(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 0, 20, 3, 10, Blocks.STONE);
        t.set(7, 1, 8, Blocks.STONE);
        t.fill(8, 1, 8, 8, 2, 8, Blocks.STONE);
        t.fill(9, 1, 8, 9, 3, 8, Blocks.STONE);
        t.set(13, 4, 5, Blocks.CRAFTING_TABLE);
        BlockPos table = t.at(13, 4, 5);
        TestBody body = t.body(9, 1, 5);
        t.go(body, Goals.use(t.level, Snapshots.of(body).stats(), table), RouteSpec.defaults()).within(500).arrives()
                .then(r -> {
                    if (feet(t, r).getY() != 4) {
                        throw new GameTestAssertException("应当上到崖顶:" + t.rel(r.body.blockPosition()));
                    }
                    sees(r, table);
                });
    }

    /** 箱子只有西面敞开,面前立着一株高草:高草是软遮挡,照样走到西面;视线上隔着它,用之前清掉就行。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void uses_a_chest_behind_tall_grass(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(12, 1, 5, Blocks.CHEST);
        t.set(12, 1, 4, Blocks.STONE).set(12, 1, 6, Blocks.STONE).set(13, 1, 5, Blocks.STONE);
        t.set(12, 2, 5, Blocks.GLASS);
        // 高草要长在泥土上,不然一次方块更新就掉了
        t.set(11, 0, 5, Blocks.GRASS_BLOCK);
        t.set(11, 1, 5, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        t.set(11, 2, 5, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
        BlockPos chest = t.at(12, 1, 5);
        TestBody body = t.body(4, 1, 5);
        var stats = Snapshots.of(body).stats();
        t.go(body, Goals.use(t.level, stats, chest), RouteSpec.defaults()).within(400).arrives().then(r -> {
            // 搜索按节点中心的眼睛挑站位:从停下的那个节点看过去,中间隔着的是高草;高草还在(只走不改)
            BlockPos node = r.body.blockPosition();
            Vec3 eye = Reach.eye(stats, Pose.STANDING, node.getX(), r.body.getY(), node.getZ());
            Sight.Trace seen = Sight.use(t.level, eye, stats.blockReach(), chest, Direction.WEST);
            if (seen == null || seen.soft().isEmpty() || !t.state(11, 2, 5).is(Blocks.TALL_GRASS)) {
                throw new GameTestAssertException("应当停在隔着高草看得见它的地方:" + t.rel(node) + " " + seen);
            }
        });
    }

    /** 站上:给那一块上面脚所在的那一格,站到它上面,托着脚的就是它。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void stands_on_a_block(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(12, 1, 5, Blocks.OAK_PLANKS);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 2, 5)), RouteSpec.defaults()).within(300).arrives()
                .then(r -> at(t, r, 12, 2, 5));
    }

    /** 靠近:停在离那一点三格以内,不必走到跟前。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void comes_near(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(4, 1, 5);
        BlockPos point = t.at(20, 1, 5);
        t.go(body, Goals.within(Goals.at(point), 0, 3), RouteSpec.defaults()).within(300).arrives().then(r -> {
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
        t.go(body, Goals.within(Goals.column(center.getX(), center.getZ()), 3, 5), RouteSpec.defaults()).within(300).arrives().then(r -> {
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
        Goal goal = Goals.anyOf(List.of(Goals.priced(Goals.within(Goals.at(t.at(10, 1, 10)), 0, 2), 500),
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
        t.go(body, Goals.within(Goals.at(t.at(12, 1, 5)), 0, 2), RouteSpec.defaults()).within(500)
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

    /**
     * 工作台摆在一块石头上,只有西面敞开;列好站位之后、开走之前,正对西面的那一格(半空,不是站位)被放上一块玻璃:
     * 走到了搜索挑的站位,在活世界上复核时看不见它,不报到达,结局是"看不见"。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void sight_blocked_after_planning_is_not_arrival(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(10, 1, 5, Blocks.STONE);
        t.set(10, 2, 5, Blocks.CRAFTING_TABLE);
        t.set(10, 2, 4, Blocks.GLASS).set(10, 2, 6, Blocks.GLASS).set(11, 2, 5, Blocks.GLASS).set(10, 3, 5, Blocks.GLASS);
        BlockPos table = t.at(10, 2, 5);
        TestBody body = t.body(4, 1, 5);
        Goals.Use use = Goals.use(t.level, Snapshots.of(body).stats(), table);
        t.change(9, 2, 5, Blocks.GLASS.defaultBlockState());
        t.go(body, use, RouteSpec.defaults()).within(300)
                .fails(Outcome.NoLineOfSight.class, o -> {
                    if (!o.target().equals(table)) {
                        throw new GameTestAssertException("看不见的应当是那张工作台:" + o);
                    }
                });
    }

    /** 要用的那一块摆在三格高的石柱顶上,身上有圆石、许改地形:站在地上就看得见、点得到,不在旁边垫柱爬上去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void does_not_pillar_up_beside_the_target(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 5, 10, 2, 5, Blocks.STONE);
        t.set(10, 3, 5, Blocks.CRAFTING_TABLE);
        BlockPos table = t.at(10, 3, 5);
        TestBody body = t.body(4, 1, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.use(t.level, Snapshots.of(body).stats(), table), NATURAL).within(300).arrives()
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

    /** 某一格可以是梯子上的一格:爬到半截停住,脚就在那一格,挂在梯子上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
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
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
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
