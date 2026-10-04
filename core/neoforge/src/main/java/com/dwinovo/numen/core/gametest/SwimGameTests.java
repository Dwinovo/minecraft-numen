package com.dwinovo.numen.core.gametest;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.plan.Breath;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 水下的路,从 {@code numen.move.to} 与 {@code numen.route.plan} 的入口:封顶的水道短到一口气游得完,她游过去,换气本能不在半路把她
 * 拽上去,回执说潜过一段水、憋了多久;计划里写出要潜的那一段;长到憋不住的不下水,回执说出那一段水下在哪、要憋多久。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class SwimGameTests {

    private static final String BATCH = "numen_swim";
    /** 水道所在的那一行。 */
    private static final int Z = 20;

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    private static void fill(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)), state, 3);
                }
            }
        }
    }

    /**
     * 地板(y = 1)下面一条封顶的水道:沿 x 从 {@code a} 到 {@code b},水两格高(脚在 y = -1),顶是地板那一层基岩,两头各一口竖井
     * 通到地面。四壁与底都是基岩。一道基岩墙在两头之间横贯整个场地,地面上过不去:两边只有这条水道连着。
     */
    private static void sealedChannel(GameTestHelper helper, int a, int b) {
        fill(helper, (a + b) / 2, 2, 0, (a + b) / 2, 26, 51, Blocks.BEDROCK.defaultBlockState());
        fill(helper, a - 1, -2, Z - 1, b + 1, 1, Z + 1, Blocks.BEDROCK.defaultBlockState());
        fill(helper, a, -1, Z, b, 0, Z, Blocks.WATER.defaultBlockState());
        fill(helper, a, 1, Z, a, 1, Z, Blocks.WATER.defaultBlockState());
        fill(helper, b, 1, Z, b, 1, Z, Blocks.WATER.defaultBlockState());
    }

    /**
     * 8 格长的封顶水道(一口气约 110 刻):{@code numen.move.to} 到对岸,她游过去,一口气到头——换气本能没有在计划内的水下把她拽上去;
     * 回执说潜过一段水、憋了多久、氧气最低到多少;一点血不掉。
     */
    @GameTest(template = "floor52", timeoutTicks = 100000, batch = BATCH)
    public static void goto_swims_a_short_sealed_channel_and_says_so(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        sealedChannel(helper, 10, 18);
        NumenPlayer companion = spawnAt(helper, "gametest_diver", new BlockPos(7, 2, Z), false);
        BlockPos there = helper.absolutePos(new BlockPos(21, 2, Z));
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(there) + ")");
        float[] lowest = {Float.MAX_VALUE};
        helper.onEachTick(() -> lowest[0] = Math.min(lowest[0], companion.getHealth()));

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "numen.move.to has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().equals(there),
                    "she did not get through the channel: " + walk.outcome());
            helper.assertTrue(walk.outcome().contains("I went under water once:")
                            && walk.outcome().contains("without a breath") && walk.outcome().contains("air down to"),
                    "the reply does not tell the one dive: " + walk.outcome());
            helper.assertTrue(lowest[0] >= companion.getMaxHealth(), "she got hurt: lowest health " + lowest[0]);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 20 格长的封顶水道(一口气约 220 刻,满氧气憋得住),离出口两格的顶上有一个封死的小气室(一格水、上面一格空气,四周是玻璃),
     * 不通地面。她游到气室底下、往东的那一步刚开始,前面那一格被一堵基岩挡住:这一步卡住,拖得比这一段剩下的憋气长。
     * <ul>
     *   <li>导航说这一段是计划内的每一刻,她的氧气都还够游完剩下的水下再留出余量({@link Breath#RESERVE})——撑不到的那一刻导航
     *       就停下那一步,不等这一步的期限(期限按一步的估价给,够她把余量憋光);</li>
     *   <li>换气本能接手,把她带进气室换气,事件说她差点淹着、游上去换了气;一点血不掉;</li>
     *   <li>唯一的路被挡死,{@code numen.move.to} 没走到,回执说潜过一段水、憋了多久。</li>
     * </ul>
     */
    @GameTest(template = "floor52", timeoutTicks = 100000, batch = BATCH)
    public static void a_planned_dive_held_up_under_water_lets_go_before_the_air_runs_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        sealedChannel(helper, 10, 30);
        // 气室:水道顶上 x = 28 那一格是水,再上面一格空气,四周与顶上是玻璃
        fill(helper, 27, 2, Z - 1, 29, 3, Z + 1, Blocks.GLASS.defaultBlockState());
        fill(helper, 28, 1, Z, 28, 1, Z, Blocks.WATER.defaultBlockState());
        fill(helper, 28, 2, Z, 28, 2, Z, Blocks.AIR.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_held_diver", new BlockPos(7, 2, Z), false);
        BlockPos there = helper.absolutePos(new BlockPos(33, 2, Z));
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(there) + ")");
        var outbox = com.dwinovo.numen.entity.EventOutbox.get(level.getServer());
        double wallX = helper.absolutePos(new BlockPos(28, 0, Z)).getX() + 0.5;
        boolean[] walled = {false};
        int[] plannedAir = {Integer.MAX_VALUE};
        float[] lowest = {Float.MAX_VALUE};
        helper.onEachTick(() -> {
            lowest[0] = Math.min(lowest[0], companion.getHealth());
            Trip trip = Trip.current(companion);
            if (trip != null && trip.plannedDive()) {
                plannedAir[0] = Math.min(plannedAir[0], companion.getAirSupply());
            }
            // 她在气室底下、往东的那一步已经开始:挡住它要去的那一格
            if (!walled[0] && companion.getX() >= wallX && companion.isEyeInFluid(FluidTags.WATER)) {
                walled[0] = true;
                fill(helper, 29, -1, Z, 29, 0, Z, Blocks.BEDROCK.defaultBlockState());
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(walled[0], "she never reached the air pocket under water");
            helper.assertTrue(walk.done(), "numen.move.to has not finished");
            helper.assertTrue(plannedAir[0] >= Breath.RESERVE,
                    "the navigation still called the dive planned with only " + plannedAir[0] + " air left");
            helper.assertTrue(lowest[0] >= companion.getMaxHealth(), "she drowned a little: lowest health " + lowest[0]);
            var told = outbox.peek(companion.getUUID()).entries().stream()
                    .filter(e -> e.type().equals(com.dwinovo.numen.agent.inbox.EventTypes.REFLEX))
                    .map(e -> e.text()).toList();
            helper.assertTrue(told.stream().anyMatch(t -> t.contains("reflex=\"breath\"") && t.contains("swam up for a breath")),
                    "no breath reflex event: " + told);
            helper.assertTrue(!walk.succeeded() && walk.outcome().contains("I went under water"),
                    "the reply does not tell the failed walk and the dive: " + walk.outcome());
            outbox.forget(companion.getUUID());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 同一条 8 格的封顶水道,{@code numen.route.plan}:计划写出要潜的那一段水下、一口气憋多久、还剩多少气;她一步没动。 */
    @GameTest(template = "floor52", timeoutTicks = 100000, batch = BATCH)
    public static void a_route_plan_tells_the_dive_on_the_way(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        sealedChannel(helper, 10, 18);
        NumenPlayer companion = spawnAt(helper, "gametest_planner", new BlockPos(7, 2, Z), false);
        BlockPos start = companion.blockPosition();
        ToolRun plan = lua(companion, "numen.route.plan({to = " + at(helper, new BlockPos(21, 2, Z)) + "})");

        succeedWhen(helper, () -> {
            helper.assertTrue(plan.done(), "route plan has not replied");
            helper.assertTrue(plan.succeeded(), "route plan failed: " + plan.reply());
            var leg = valueIn(plan.reply()).getAsJsonObject().getAsJsonArray("legs").get(0).getAsJsonObject();
            helper.assertTrue(leg.has("dives") && leg.getAsJsonArray("dives").size() == 1
                            && leg.getAsJsonArray("dives").get(0).getAsJsonObject().get("seconds").getAsDouble() > 0
                            && leg.getAsJsonArray("dives").get(0).getAsJsonObject().has("spare"),
                    "the plan does not tell the dive: " + plan.reply());
            helper.assertTrue(companion.blockPosition().equals(start), "planning moved the body");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 28 格长的封顶水道(一口气约 290 刻,满氧气安全地只憋得住 240 刻),是两边唯一的路:{@code numen.move.to} 不下水,回执说那一段
     * 水下从哪儿到哪儿、要憋多久、能憋多久,有水下呼吸才走得了;她没进过水,一点血不掉。
     */
    @GameTest(template = "floor52", timeoutTicks = 100000, batch = BATCH)
    public static void goto_will_not_dive_a_sealed_channel_too_long_to_hold(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        sealedChannel(helper, 8, 36);
        NumenPlayer companion = spawnAt(helper, "gametest_breathless", new BlockPos(5, 2, Z), false);
        BlockPos there = helper.absolutePos(new BlockPos(39, 2, Z));
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(there) + ")");
        boolean[] wet = {false};
        helper.onEachTick(() -> wet[0] |= companion.isInWater());

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "numen.move.to has not finished");
            helper.assertTrue(!walk.succeeded() && walk.outcome().contains("swims under water from")
                            && walk.outcome().contains("without a breath") && walk.outcome().contains("water breathing"),
                    "the reply does not say the way is too long under water: " + walk.outcome());
            helper.assertTrue(!wet[0], "she went into the water");
            helper.assertTrue(companion.getHealth() >= companion.getMaxHealth(), "she got hurt");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
