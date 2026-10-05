package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;
import static com.dwinovo.numen.pathing.gametest.Trial.LONG;
import static com.dwinovo.numen.pathing.gametest.Trial.TALL;

import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.PlanQuery;
import com.dwinovo.numen.pathing.search.Goals;
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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 预算与长途:预算用完不说成无路;长途分段接上、段与段之间不停;挖隧道的长路一段一段挖过去;要一路搭桥时先走交出的一段、
 * 边走边搜;按高度的目标垫五十格不被当成停滞;暂停之后照留着的路接着走;路伸进没加载的区块时说"未加载"。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class BudgetGameTests {

    private static final String BATCH = "pathing_budget";
    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().changes(true).consent(false).build();

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /**
     * 一道基岩墙挡在身前,口在二十格外:每次搜索只许展开两百个节点,搜不到口就用完了——结局是"预算用完",不说成无路;
     * 同一处按出厂预算规划,搜得出绕过去的路。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void running_out_of_budget_is_not_no_route(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 0, 10, 3, 33, Blocks.BEDROCK);
        TestBody body = t.body(8, 1, 15);
        NavRequest request = NavRequest.to(Goals.at(t.at(13, 1, 15)), RouteSpec.defaults());
        t.plan(body, PlanQuery.of(request.goal(), request.spec(), 1), plan -> {
            if (plan.candidates().isEmpty()) {
                throw new GameTestAssertException("出厂预算应当搜得出绕过去的路:" + plan.outcome());
            }
            t.go(body, request.withBudget(200)).within(300).fails(Outcome.OutOfBudget.class).then(Scenes::unaltered);
        });
    }

    /**
     * 两百格长的平地,一次搜索的快照装不下:一段一段搜、一段一段接上,从起步到快到终点,中间一刻也没停下来等搜索。
     * 每刻至少占五毫秒({@link Trial.Run#paced}),提前搜下一段来不来得及才与真实服务器可比。
     */
    @GameTest(template = LONG, batch = BATCH, timeoutTicks = 2000)
    public static void joins_long_segments_without_stopping(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(2, 1, 12);
        BlockPos goal = t.at(210, 1, 12);
        boolean[] started = {false};
        int[] still = {0};
        t.go(body, Goals.at(goal), RouteSpec.defaults()).within(1800).paced(5)
                .during(r -> {
                    var v = r.body.getDeltaMovement();
                    double speed = Math.sqrt(v.x * v.x + v.z * v.z);
                    if (!started[0]) {
                        started[0] = speed > 0.05;
                        return;
                    }
                    if (goal.getX() - r.body.getX() < 4) {
                        return;
                    }
                    still[0] = speed < 0.01 ? still[0] + 1 : 0;
                    if (still[0] > 2) {
                        throw new GameTestAssertException("半路停下来了:" + t.rel(r.body.blockPosition()) + " " + r.navigation);
                    }
                })
                .arrives();
    }

    /**
     * 身前是九十五格厚的整片石头(顶到场地的屋顶,翻不过去),去处在石头里八十九格深,手上一把钻石镐,许挖许放:每次搜索
     * 只许展开八千个节点,一次搜不到头(规划说预算用完),照样一段一段挖过去,挖到去处。
     */
    @GameTest(template = LONG, batch = BATCH, timeoutTicks = 7000)
    public static void tunnels_a_long_way_by_partial_routes(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(6, 1, 0, 100, 14, 23, Blocks.STONE);
        TestBody body = t.body(2, 1, 12);
        body.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        NavRequest request = NavRequest.to(Goals.at(t.at(94, 1, 12)), NATURAL).withBudget(8000);
        t.plan(body, new PlanQuery(request.goal(), request.spec(), 1, request.budget()), plan -> {
            if (!(plan.outcome() instanceof Outcome.OutOfBudget)) {
                throw new GameTestAssertException("一次搜索应当搜不到头:" + plan.outcome() + " " + plan.candidates().size());
            }
            t.go(body, request).within(6800).paced(2).arrives().then(r -> {
                if (r.report.bill().digs().size() < 2 * 88) {
                    throw new GameTestAssertException("没挖出隧道:" + r.report.bill().digs().size() + " 格");
                }
            });
        });
    }

    /**
     * 两块基岩台子之间一百五十格宽的空隙(跳下去摔不起,底下的地也挖不出路),身上有三叠圆石,许挖许放:过去只能一路搭桥,
     * 对岸远在一次搜索的快照之外。许放块时搜索在空中四面铺开;每段搜索展开到先交半程的节点数就交出朝对岸的一段,她先走这一段,
     * 快走完时从桥头(快照里还没有那块桥)接着搜下一段。起步那段每刻按真实服务器的五十毫秒走,四十刻(两秒)以内就动起来;
     * 一段段搭过去,到对岸。
     */
    @GameTest(template = LONG, batch = BATCH, timeoutTicks = 7000)
    public static void starts_bridging_a_wide_chasm_before_the_whole_route_is_found(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(0, 1, 0, 9, 4, 23, Blocks.BEDROCK);
        t.fill(160, 1, 0, 180, 4, 23, Blocks.BEDROCK);
        TestBody body = t.body(6, 5, 12);
        for (int i = 0; i < 3; i++) {
            Trial.give(body, new ItemStack(Items.COBBLESTONE, 64));
        }
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        var start = body.position();
        boolean[] moving = {false};
        t.go(body, Goals.at(t.at(164, 5, 12)), NATURAL).within(6800)
                .during(r -> {
                    try {
                        Thread.sleep(moving[0] ? 5 : 50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (moving[0]) {
                        return;
                    }
                    moving[0] = r.body.position().distanceToSqr(start) > 0.25;
                    if (!moving[0] && r.ticks > 40) {
                        throw new GameTestAssertException("两秒了还站着等搜索:" + r.navigation);
                    }
                })
                .arrives();
    }

    /** 按高度的目标:在平地上垫五十格高的柱子上去;一路对外都是"在推进",不被当成停滞。 */
    @GameTest(template = TALL, batch = BATCH, timeoutTicks = 3000)
    public static void pillars_fifty_blocks_up_to_a_level(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(8, 1, 8);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 64));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        int level = t.origin.getY() + 51;
        t.go(body, Goals.level(level), NATURAL).within(2800)
                .during(r -> {
                    if (r.ticks > 1 && !r.navigation.progressing()) {
                        throw new GameTestAssertException("爬到 " + t.rel(r.body.blockPosition()) + " 时不算在推进了");
                    }
                })
                .arrives().then(r -> {
                    if (r.body.blockPosition().getY() != level) {
                        throw new GameTestAssertException("没到那一层:" + t.rel(r.body.blockPosition()));
                    }
                });
    }

    /**
     * 走在一条绕远的走廊里,半路暂停四十刻:这期间身体站住;暂停时走廊墙上开了一个直通去处的口,接着走时照留着的路走下去
     * (不重搜,也就不拐进新口),而且接着走的第一刻就动起来,不等搜索。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void resumes_the_kept_route_without_searching(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        // 一条 U 形走廊:从 (4,5) 往东到 x=30,拐到 z=11,再往西回到 (4,11);去处在 (4,11)
        t.fill(2, 1, 3, 32, 3, 13, Blocks.BEDROCK);
        t.fill(3, 1, 4, 30, 2, 6, Blocks.AIR);
        t.fill(28, 1, 4, 30, 2, 12, Blocks.AIR);
        t.fill(3, 1, 10, 30, 2, 12, Blocks.AIR);
        TestBody body = t.body(4, 1, 5);
        int[] pausedAt = {-1};
        boolean[] resumed = {false};
        int[] resumedAt = {-1};
        t.go(body, Goals.at(t.at(4, 1, 11)), RouteSpec.defaults()).within(700)
                .during(r -> {
                    double x = r.body.getX() - t.origin.getX();
                    double z = r.body.getZ() - t.origin.getZ();
                    var v = r.body.getDeltaMovement();
                    double speed = Math.sqrt(v.x * v.x + v.z * v.z);
                    if (pausedAt[0] < 0 && x >= 12 && z < 7) {
                        pausedAt[0] = r.ticks;
                        r.navigation.pause();
                        // 开一个直通的口:从走廊的这一段横穿到回来的那一段
                        for (int y = 1; y <= 2; y++) {
                            for (int cz = 7; cz <= 9; cz++) {
                                t.change((int) Math.floor(x), y, cz, Blocks.AIR.defaultBlockState());
                            }
                        }
                    } else if (pausedAt[0] >= 0 && !resumed[0]) {
                        if (r.ticks - pausedAt[0] > 10 && speed > 0.01) {
                            throw new GameTestAssertException("暂停时身体还在走:" + speed);
                        }
                        if (r.ticks - pausedAt[0] >= 40) {
                            resumed[0] = true;
                            resumedAt[0] = r.ticks;
                            r.navigation.resume();
                        }
                    } else if (resumed[0]) {
                        if (r.ticks - resumedAt[0] == 3 && speed < 0.01) {
                            throw new GameTestAssertException("接着走三刻了还没动起来");
                        }
                        if (z > 7 && z < 10 && x < 27) {
                            throw new GameTestAssertException("接着走时拐进了暂停时开的口:" + t.rel(r.body.blockPosition()));
                        }
                    }
                })
                .arrives();
    }

    /**
     * 只搜不走:去处在两百格外,一次搜索的快照装不下那么远——走得到的都搜过了,路却伸出了快照、那边是什么不知道:结局是
     * "未加载",不说成无路或预算用完;身体一动不动。
     */
    @GameTest(template = LONG, batch = BATCH, timeoutTicks = 400)
    public static void a_route_into_unloaded_chunks_is_unloaded(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(2, 1, 12);
        BlockPos start = body.blockPosition();
        t.plan(body, PlanQuery.of(Goals.at(t.at(210, 1, 12)), RouteSpec.defaults(), 1), plan -> {
            if (!(plan.outcome() instanceof Outcome.Unloaded)) {
                throw new GameTestAssertException("结局应是未加载:" + plan.outcome() + " " + plan.candidates().size());
            }
            if (!body.blockPosition().equals(start)) {
                throw new GameTestAssertException("只搜不走,身体却动了");
            }
            body.leave();
            helper.succeed();
        });
    }
}
