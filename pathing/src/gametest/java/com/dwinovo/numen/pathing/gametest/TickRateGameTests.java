package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import java.util.concurrent.atomic.AtomicInteger;

import com.dwinovo.numen.pathing.api.Bill;
import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.PlanQuery;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Searches;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.gametest.framework.AfterBatch;
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
 * tick 速率:{@code /tick rate} 调到 100 之后,挖墙(破块冷却)、搭桥(放置时转头瞄面)、绕墙走,结果与规划的一样——挖掉、放下的
 * 正是规划的那几格,照样到。
 *
 * <p>服务端的游戏逻辑按刻走,不看刻速率;刻速率只改墙钟上一刻多长,也就改了"搜索在工作线程上跑完要几刻"——刻越快,搜索的
 * 结论晚到的刻数越多。测试服务器本来就不等墙钟(一刻做完接着下一刻),这一批再让搜索的线程池一直排着一队睡着的活,
 * 每次搜索的结论都要晚到很多刻,看结果变不变。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class TickRateGameTests {

    private static final String BATCH = "pathing_tickrate";
    private static final float RAISED = 100;
    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build();

    private static float before;

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
        before = level.getServer().tickRateManager().tickrate();
        level.getServer().tickRateManager().setTickRate(RAISED);
    }

    @AfterBatch(batch = BATCH)
    public static void restore(ServerLevel level) {
        level.getServer().tickRateManager().setTickRate(before);
    }

    /** 搜索线程池里排着的睡觉活:每刻补足,让派出去的搜索都排在它们后面。 */
    private static final class Backlog {
        private static final int DEPTH = 32;
        private static final long NAP_MILLIS = 5;
        private final AtomicInteger queued = new AtomicInteger();

        void tick(Trial.Run r) {
            while (queued.get() < DEPTH) {
                queued.incrementAndGet();
                Searches.submit(cancelled -> {
                    try {
                        Thread.sleep(NAP_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    queued.decrementAndGet();
                    return null;
                });
            }
        }
    }

    /**
     * 先只搜不走地规划一次,再在排着队的搜索下真走:实际账里挖掉、放下的格与规划的那条路要挖、要放的一格不差,照样到。
     */
    private static void sameAsPlanned(Trial t, TestBody body, NavRequest request) {
        t.plan(body, PlanQuery.of(request.goal(), request.spec(), 1), plan -> {
            if (plan.candidates().isEmpty()) {
                throw new GameTestAssertException("没规划出路:" + plan.outcome());
            }
            Bill planned = plan.candidates().get(0).bill();
            Backlog backlog = new Backlog();
            t.go(body, request).within(1800).during(backlog::tick).arrives().then(r -> {
                Bill actual = r.report.bill();
                if (!actual.digs().equals(planned.digs()) || !actual.places().equals(planned.places())) {
                    throw new GameTestAssertException("与规划的不一样:规划挖 " + planned.digs() + " 放 " + planned.places()
                            + ",实际挖 " + actual.digs() + " 放 " + actual.places());
                }
            });
        });
    }

    /** 三格厚、三格高的泥土墙横贯场地,手上铁锹,许改自然地形:挖的正是规划要挖的那几格(破块冷却不改结果)。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 2000)
    public static void digs_the_planned_cells(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 0, 12, 3, 39, Blocks.DIRT);
        TestBody body = t.body(5, 1, 20);
        body.getInventory().setItem(0, new ItemStack(Items.IRON_SHOVEL));
        sameAsPlanned(t, body, NavRequest.to(Goals.at(t.at(15, 1, 20)), NATURAL));
    }

    /** 两块基岩台子之间一道四格宽的沟,身上有圆石:搭桥过去,放的正是规划要放的那几格(放置时转头瞄面不改结果)。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 2000)
    public static void bridges_the_planned_cells(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(0, 1, 0, 9, 4, 39, Blocks.BEDROCK);
        t.fill(14, 1, 0, 39, 4, 39, Blocks.BEDROCK);
        TestBody body = t.body(6, 5, 20);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        sameAsPlanned(t, body, NavRequest.to(Goals.at(t.at(17, 5, 20)), NATURAL));
    }

    /** 中间一堵墙,绕过去;一格不改。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 2000)
    public static void walks_around_the_same_wall(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(8, 1, 2, 8, 2, 10, Blocks.STONE);
        TestBody body = t.body(4, 1, 6);
        Backlog backlog = new Backlog();
        t.go(body, Goals.at(t.at(12, 1, 6)), RouteSpec.defaults()).within(1800).during(backlog::tick).arrives()
                .then(Scenes::unaltered);
    }
}
