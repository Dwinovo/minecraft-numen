package com.dwinovo.numen.gametest;

import static com.dwinovo.numen.gametest.GameTestKit.*;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 她在水里的三件事分开管:在水面上浮着(身体的基本面,不发消息)、真缺氧才上来换气(换气本能,每次只报一条)、
 * 自己的状态进 {@code <runtime_state>}。场地是一只五乘五的水箱:基岩围壁,底是模板的地板,水从地板上一层起;
 * 事件条数从她的收件箱({@link GameTestKit#events})里数:世界上发生的事都进那里。
 */
public class FloatGameTests {

    private static final String BATCH = "numen_float";
    /** 水箱内部 x、z 的范围,与地板上第一层水的高度。 */
    private static final int LOW = 16;
    private static final int HIGH = 20;
    private static final int BED = 2;

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    private static void fill(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)), block.defaultBlockState(), 3);
                }
            }
        }
    }

    /**
     * {@code depth} 格深的水箱,基岩围壁高过水面一格(浮着出不去)。{@code roof} 时顶上盖一层这种方块,箱里没有一格空气。
     */
    private static void tank(GameTestHelper helper, int depth, Block roof) {
        int top = BED + depth;
        fill(helper, LOW - 1, 1, LOW - 1, HIGH + 1, top, HIGH + 1, Blocks.BEDROCK);
        fill(helper, LOW, BED, LOW, HIGH, top, HIGH, Blocks.AIR);
        fill(helper, LOW, BED, LOW, HIGH, top - 1, HIGH, Blocks.WATER);
        if (roof != null) {
            fill(helper, LOW, top, LOW, HIGH, top, HIGH, roof);
        }
    }

    /** 这具身体至今发出去的本能事件。 */
    private static List<EventQueue.Entry> reflexes(ServerLevel level, NumenPlayer companion) {
        return GameTestKit.events(companion).stream()
                .filter(e -> e.type().equals(EventTypes.REFLEX)).toList();
    }

    private static void finish(ServerLevel level, NumenPlayer companion) {
        CompanionFactory.despawn(level.getServer(), companion);
    }

    /**
     * 深水水面上 30 秒不按键(闲着,或手上的活是站着等):漂浮让她一直浮着,头没进过水,没往下沉;身体没有做什么值得说的事,
     * 一条事件都没有。创造模式同样(不扣氧气,浮着不靠换气)。{@code waiting} 时她手上是 {@code numen.time.wait},占着任务槽。
     */
    private static void floatsAtTheSurface(GameTestHelper helper, boolean creative, boolean waiting) {
        ServerLevel level = helper.getLevel();
        tank(helper, 8, null);
        BlockPos top = new BlockPos(18, BED + 7, 18);
        NumenPlayer companion = spawnAt(helper, creative ? "gametest_floater_creative" : "gametest_floater", top, creative);
        double topY = helper.absolutePos(top).getY();
        ToolRun wait = waiting ? lua(companion, "numen.time.wait(30)") : null;
        int[] ticks = {0};
        double[] lowest = {Double.MAX_VALUE};
        boolean[] headUnder = {false};
        helper.onEachTick(() -> {
            if (++ticks[0] > 20) {
                lowest[0] = Math.min(lowest[0], companion.getY());
                headUnder[0] |= companion.isEyeInFluid(FluidTags.WATER);
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(ticks[0] >= 580, "still idling");
            helper.assertTrue(wait == null || !wait.done(), "the wait ended before the 30 seconds were up");
            helper.assertTrue(!headUnder[0], "her head went under water while idle at the surface");
            helper.assertTrue(lowest[0] > topY - 1.5, "she sank: lowest y " + lowest[0] + " vs the top water cell at " + topY);
            helper.assertTrue(reflexes(level, companion).isEmpty(),
                    "floating said something: " + reflexes(level, companion));
            finish(level, companion);
        });
    }

    @GameTest(template = "floor20", timeoutTicks = 1000, batch = BATCH)
    public static void idle_at_the_surface_of_deep_water_floats_and_says_nothing(GameTestHelper helper) {
        floatsAtTheSurface(helper, false, false);
    }

    @GameTest(template = "floor20", timeoutTicks = 1000, batch = BATCH)
    public static void waiting_on_deep_water_floats_and_says_nothing(GameTestHelper helper) {
        floatsAtTheSurface(helper, false, true);
    }

    @GameTest(template = "floor20", timeoutTicks = 1000, batch = BATCH)
    public static void creative_idle_in_deep_water_floats(GameTestHelper helper) {
        floatsAtTheSurface(helper, true, false);
    }

    /**
     * 潜到水箱半腰某一格后闲置:她整个人在水下,不被往上拽(沉向箱底,一格都没升过);氧气不够游回水面了,换气本能接管一次、
     * 只报一条(不急,随下一次调模型捎带),换满气才松手,之后漂浮接着让她浮在水面上,不再触发第二次。
     */
    @GameTest(template = "floor20", timeoutTicks = 1500, batch = BATCH)
    public static void idle_under_water_stays_down_until_the_air_runs_low_then_surfaces_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        tank(helper, 8, null);
        BlockPos spot = new BlockPos(18, BED + 4, 18);
        NumenPlayer companion = spawnAt(helper, "gametest_diver_idle", spot, false);
        double startY = helper.absolutePos(spot).getY();
        int[] ticks = {0};
        boolean[] rose = {false};
        helper.onEachTick(() -> {
            if (++ticks[0] <= 100 && companion.getY() > startY + 0.3) {
                rose[0] = true;
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(ticks[0] >= 800, "still waiting");
            helper.assertTrue(!rose[0], "she was pulled up while idle under water within the first 5 seconds");
            List<EventQueue.Entry> told = reflexes(level, companion);
            helper.assertTrue(told.size() == 1, "expected exactly one breath event, got " + told);
            helper.assertTrue(!told.get(0).urgent() && told.get(0).text().contains("reflex=\"breath\"")
                            && told.get(0).text().contains("idle under water"),
                    "the breath event is not the calm idle one: " + told.get(0));
            helper.assertTrue(!companion.isEyeInFluid(FluidTags.WATER), "she did not come up for air");
            helper.assertTrue(companion.getAirSupply() >= companion.getMaxAirSupply(), "she did not get her air back");
            helper.assertTrue(companion.getHealth() >= companion.getMaxHealth(), "she got hurt");
            finish(level, companion);
        });
    }

    /**
     * 水下的活:三格深的水箱,顶盖是一层黏土、角上留一格空气。{@code numen.work.dig} 从水里仰着徒手挖盖上的三块(水下慢,一口气挖不完;
     * 水箱底的格挖不了——挖开会灌水,{@code numen.work.dig} 当场拒绝,所以挖的是头顶上的)。半途氧气不够游到最近的透气口换气,
     * 事件只报一条、说顶掉了这件活;换满气才松手,回来接着挖完,三块都挖掉、活的结论是成功。头一露出水面就松手的话,她带着没回几点的气
     * 回去接着挖,又触发,永远挖不完。
     */
    @GameTest(template = "floor20", timeoutTicks = 3000, batch = BATCH)
    public static void digging_under_water_surfaces_once_for_air_and_finishes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        tank(helper, 3, Blocks.CLAY);
        int roof = BED + 3;
        helper.getLevel().setBlock(helper.absolutePos(new BlockPos(HIGH, roof, HIGH)), Blocks.AIR.defaultBlockState(), 3);
        List<BlockPos> cells = List.of(new BlockPos(16, roof, 18), new BlockPos(17, roof, 18), new BlockPos(18, roof, 18));
        NumenPlayer companion = spawnAt(helper, "gametest_underwater_digger", new BlockPos(16, BED, 17), false);
        ToolRun[] dig = new ToolRun[1];
        succeedWhen(helper, () -> {
            if (dig[0] == null) {
                dig[0] = lua(companion, "numen.work.dig({" + String.join(", ",
                        cells.stream().map(c -> xyz(helper.absolutePos(c))).toList()) + "})");
            }
            helper.assertTrue(dig[0].done(), "work dig has not finished");
            helper.assertTrue(dig[0].succeeded(), "the underwater dig did not succeed: " + dig[0].outcome());
            for (BlockPos cell : cells) {
                helper.assertTrue(!level.getBlockState(helper.absolutePos(cell)).is(Blocks.CLAY), "clay left at " + cell);
            }
            List<EventQueue.Entry> told = reflexes(level, companion);
            helper.assertTrue(told.size() == 1, "expected exactly one breath event, got " + told);
            helper.assertTrue(!told.get(0).urgent() && told.get(0).text().contains("I paused")
                            && told.get(0).text().contains("carries on once my air is back"),
                    "the breath event does not say what it interrupted: " + told.get(0));
            helper.assertTrue(companion.getHealth() >= companion.getMaxHealth(), "she got hurt");
            finish(level, companion);
        });
    }

    /**
     * 冰面封顶、箱里一格空气都没有:游不到任何能呼吸的地方,本能接管时只发一条,而且是急件(她得当场决定挖气孔还是想别的办法);
     * 之后接着被封着也不再刷。
     */
    @GameTest(template = "floor20", timeoutTicks = 1000, batch = BATCH)
    public static void sealed_under_ice_raises_one_urgent_alarm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        tank(helper, 4, Blocks.PACKED_ICE);
        NumenPlayer companion = spawnAt(helper, "gametest_sealed", new BlockPos(18, BED + 1, 18), false);
        int[] ticks = {0};
        int[] raisedAt = {-1};
        helper.onEachTick(() -> {
            ticks[0]++;
            if (raisedAt[0] < 0 && !reflexes(level, companion).isEmpty()) {
                raisedAt[0] = ticks[0];
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(raisedAt[0] >= 0 && ticks[0] >= raisedAt[0] + 100, "no alarm yet (tick " + ticks[0] + ")");
            List<EventQueue.Entry> told = reflexes(level, companion);
            helper.assertTrue(told.size() == 1, "expected exactly one alarm, got " + told);
            helper.assertTrue(told.get(0).urgent() && told.get(0).text().contains("reflex=\"breath\"")
                            && told.get(0).text().contains("sealed ceiling"),
                    "the alarm is not the urgent sealed-ceiling one: " + told.get(0));
            helper.assertTrue(companion.getHealth() >= companion.getMaxHealth(), "she drowned before the alarm helped");
            finish(level, companion);
        });
    }
}
