package com.dwinovo.numen.gametest;

import static com.dwinovo.numen.gametest.GameTestKit.*;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.nav.Trip;
import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.google.gson.JsonElement;

import java.lang.reflect.Field;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 绕路的代价,从入口量(套件 {@code numen_detour},只用 {@code -Pgametest=numen_detour} 手动选跑,不在日常批里):{@code numen.move.to}
 * 走一趟,每一段搜索交出的结论(停因、展开数、证到的界、离目标最近处被拒的原因)一段一段记进日志({@code [detour-probe]} 开头的行),
 * 收场时再记一行总账和模型读到的回执。每段两个比值,分母都是第一段起点的估价(还没抬高过,就是直线估价):
 * <ul>
 *   <li>起点估价:搜索对"从这一段起点到目标至少多少刻"的下界,含这一趟记忆里抬高的增量;</li>
 *   <li>总价下界:到这一段起点为止已经定下要付的代价(走完的步,加上装进去还没走完的路线)加上起点估价。</li>
 * </ul>
 *
 * <p>三组场景:深矿洞在河底之下、目标在河面上({@link #river_above_a_deep_cave}、{@link #narrow_river_above_a_deep_cave}、
 * {@link #wide_river_above_a_deep_cave_with_a_fast_pick}),同样的格局河底换成石头、没有水({@link #dry_riverbed_above_a_deep_cave}),
 * 正常绕路——一道基岩墙,口在不同远处({@link #a_wall_with_a_gap_far_to_the_side} 等)。用例断言的只有现状成立的:一趟在时限内收场;
 * 河底换成石头的和绕墙的还要到达。数据读的是段状态机里最后一次搜索的结论,走反射,不改寻路的任何东西。
 */
public class DetourGameTests {

    /** 河的几条各占一批:批内的用例并行跑,同时搜索会抢同一个搜索线程池,墙钟就不是一条路自己的。 */
    private static final String RIVER = "numen_detour_river";
    private static final String DRY = "numen_detour_dry";
    private static final String NARROW = "numen_detour_narrow";
    private static final String HASTY = "numen_detour_hasty";
    private static final String BATCH = "numen_detour";

    @BeforeBatch(batch = RIVER)
    public static void prepareRiver(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    @BeforeBatch(batch = DRY)
    public static void prepareDry(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    @BeforeBatch(batch = NARROW)
    public static void prepareNarrow(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    @BeforeBatch(batch = HASTY)
    public static void prepareHasty(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    // ==================== 场景 ====================

    /** 河的场地:84 × 40 × 104,河向沿 z(两头的石坝离河中央 51 格,比两岸远),河中央在 x=42。地面顶 y=31,河底到水面共 y=24..30。 */
    private static final int RIVER_X = 84;
    private static final int RIVER_Z = 104;
    private static final int MID_X = 42;
    private static final int MID_Z = 52;
    private static final int GROUND_TOP = 31;

    private static void put(GameTestHelper helper, int x, int y, int z, BlockState state) {
        helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)), state, 2);
    }

    private static void column(GameTestHelper helper, int x, int z, int from, int to, BlockState state) {
        for (int y = from; y <= to; y++) {
            put(helper, x, y, z, state);
        }
    }

    /**
     * 搭河,河宽 {@code 2 * half} 格。两岸:石头到 y=29,泥土 y=30,草 y=31。河道:石头到 y=23,砾石 y=24,沙 y=25..26,水 y=27..30
     * (四格深,水面比岸低一格);{@code wet} 为假时河道是实心石头到 y=29、没有水。z 的两头各立一道石坝,水不外流。河中央正下方一间
     * 7×7×3 的矿洞。
     */
    private static void river(GameTestHelper helper, boolean wet, int half) {
        int left = MID_X - half;
        int right = MID_X + half - 1;
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < RIVER_X; x++) {
            for (int z = 0; z < RIVER_Z; z++) {
                if (z == 0 || z == RIVER_Z - 1) {
                    column(helper, x, z, 0, GROUND_TOP, stone);
                } else if (x < left || x > right) {
                    column(helper, x, z, 0, 29, stone);
                    put(helper, x, 30, z, Blocks.DIRT.defaultBlockState());
                    put(helper, x, 31, z, Blocks.GRASS_BLOCK.defaultBlockState());
                } else if (wet) {
                    column(helper, x, z, 0, 23, stone);
                    put(helper, x, 24, z, Blocks.GRAVEL.defaultBlockState());
                    column(helper, x, z, 25, 26, Blocks.SAND.defaultBlockState());
                    column(helper, x, z, 27, 30, Blocks.WATER.defaultBlockState());
                } else {
                    column(helper, x, z, 0, 29, stone);
                }
            }
        }
        // 最底下一层是基岩:往下挖会掉出模板、挖进模板底下的世界
        for (int x = 0; x < RIVER_X; x++) {
            for (int z = 0; z < RIVER_Z; z++) {
                put(helper, x, 0, z, Blocks.BEDROCK.defaultBlockState());
            }
        }
        for (int x = MID_X - 3; x <= MID_X + 3; x++) {
            for (int z = MID_Z - 3; z <= MID_Z + 3; z++) {
                column(helper, x, z, 1, 3, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** 她:钻石镐(挖一路不坏)、一百二十八块圆石(垫路料),dig 和 place 都许(出厂的 {@code numen.move.to} 就是),在 rel 那一格。 */
    private static NumenPlayer miner(GameTestHelper helper, String name, BlockPos rel, boolean stocked, int efficiency) {
        NumenPlayer her = spawnAt(helper, name, rel, false);
        if (stocked) {
            ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
            if (efficiency > 0) {
                pick.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                        .getHolderOrThrow(Enchantments.EFFICIENCY), efficiency);
            }
            her.getInventory().add(pick);
            her.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
            her.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        }
        return her;
    }

    /** 深矿洞里的她,去河中央、地面以下一格的 y=30(湿:水面那一格;干:石头河底上站着)。 */
    private static void fromTheCave(GameTestHelper helper, String label, boolean wet, int half, int efficiency,
                                    boolean mustArrive) {
        river(helper, wet, half);
        NumenPlayer her = miner(helper, "gametest_" + label, new BlockPos(MID_X, 1, MID_Z), true, efficiency);
        BlockPos target = helper.absolutePos(new BlockPos(MID_X, 30, MID_Z));
        probe(helper, label, her, target, mustArrive);
    }

    /** 河向上:矿洞在河底之下 21 格,河面宽 48 格。 */
    @GameTest(template = "numen:detour_river", timeoutTicks = 100000, batch = RIVER)
    public static void river_above_a_deep_cave(GameTestHelper helper) {
        fromTheCave(helper, "river", true, 24, 0, false);
    }

    /**
     * 河宽 72 格,镐上附了效率五:挖一格只要一两刻,同样的刻数里搜索走得更多,这一趟的总预算先于"一趟最长走五分钟"用完——
     * 收场的是寻路的预算用完,回执是寻路给的那一句。
     */
    @GameTest(template = "numen:detour_river", timeoutTicks = 100000, batch = HASTY)
    public static void wide_river_above_a_deep_cave_with_a_fast_pick(GameTestHelper helper) {
        fromTheCave(helper, "hasty", true, 36, 5, false);
    }

    /** 同样的河,只有 20 格宽:岸近得多,横着挖到岸边再上去只要十格。 */
    @GameTest(template = "numen:detour_river", timeoutTicks = 100000, batch = NARROW)
    public static void narrow_river_above_a_deep_cave(GameTestHelper helper) {
        fromTheCave(helper, "narrow", true, 10, 0, false);
    }

    /** 同一处,河道换成实心石头、没有水:一直往上挖就到。 */
    @GameTest(template = "numen:detour_river", timeoutTicks = 100000, batch = DRY)
    public static void dry_riverbed_above_a_deep_cave(GameTestHelper helper) {
        fromTheCave(helper, "dry", false, 24, 0, true);
    }

    /**
     * 一道基岩墙(地板、围边、墙都是基岩,挖不动,她身上也没有垫路料),墙在 x=50,她在 x={@code from}、目标在 x={@code to},正对着;
     * 墙上三格宽的口在正对线侧面 {@code side} 格的地方。要绕才过得去。
     */
    private static void wall(GameTestHelper helper, String label, int side, int from, int to) {
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        int sizeX = 100;
        int sizeZ = 130;
        int middle = 65;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                put(helper, x, 0, z, bedrock);
                boolean ring = x == 0 || x == sizeX - 1 || z == 0 || z == sizeZ - 1;
                boolean gap = x == 50 && z >= middle + side && z < middle + side + 3;
                if (ring || (x == 50 && !gap)) {
                    column(helper, x, z, 1, 22, bedrock);
                }
            }
        }
        NumenPlayer her = miner(helper, "gametest_" + label, new BlockPos(from, 1, middle), false, 0);
        probe(helper, label, her, helper.absolutePos(new BlockPos(to, 1, middle)), true);
    }

    /** 墙上的口开在正对线上:不用绕,直走 20 格。 */
    @GameTest(template = "numen:detour_wall", timeoutTicks = 100000, batch = BATCH)
    public static void a_wall_with_a_gap_straight_ahead(GameTestHelper helper) {
        wall(helper, "wall0", 0, 40, 60);
    }

    /** 口在侧面 20 格:绕一圈约是直线的 2.2 倍。 */
    @GameTest(template = "numen:detour_wall", timeoutTicks = 100000, batch = BATCH)
    public static void a_wall_with_a_gap_far_to_the_side(GameTestHelper helper) {
        wall(helper, "wall20", 20, 40, 60);
    }

    /** 口在侧面 45 格:绕一圈约是直线的 4.6 倍。 */
    @GameTest(template = "numen:detour_wall", timeoutTicks = 100000, batch = BATCH)
    public static void a_wall_with_a_gap_very_far_to_the_side(GameTestHelper helper) {
        wall(helper, "wall45", 45, 40, 60);
    }

    /** 口在侧面 60 格:绕一圈约是直线的 6 倍。 */
    @GameTest(template = "numen:detour_wall", timeoutTicks = 100000, batch = BATCH)
    public static void a_wall_with_a_gap_at_the_far_end(GameTestHelper helper) {
        wall(helper, "wall60", 60, 40, 60);
    }

    /** 目标就在墙那一边、隔着四格,口在侧面 60 格:直线只有四格,绕一圈一百二十多格。 */
    @GameTest(template = "numen:detour_wall", timeoutTicks = 100000, batch = BATCH)
    public static void a_wall_just_ahead_with_a_gap_at_the_far_end(GameTestHelper helper) {
        wall(helper, "wallnear", 60, 48, 52);
    }

    // ==================== 量 ====================

    /** 从入口 {@code numen.move.to} 开走,每刻看一眼段状态机,收场时记总账。 */
    private static void probe(GameTestHelper helper, String label, NumenPlayer her, BlockPos target, boolean mustArrive) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        Probe probe = new Probe(label, her, origin);
        ToolRun walk = lua(her, "return numen.move.to(" + xyz(target) + ")");
        probe.walk = walk;
        helper.onEachTick(probe::sample);
        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move to has not finished");
            probe.summarise(walk);
            if (mustArrive) {
                helper.assertTrue(walk.succeeded(), "she did not get there: " + walk.outcome());
            }
            // 一趟走到时限还没到:回执说走了多久、停在哪、最近处被什么挡住(回执在放手之前拼,读得到这一趟的寻路)
            String said = walk.outcome();
            if (said.startsWith("timed out")) {
                helper.assertTrue(said.startsWith("timed out after ") && !said.contains("progress had stopped")
                                && said.contains("nearest to the target"),
                        "the timeout receipt does not say what blocked the nearest approach: " + said);
            }
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
    }

    /** 一趟的读数:段状态机里最后一次搜索的结论(走反射读 {@code Driver} 的字段),一换就记一行。 */
    private static final class Probe {

        private final String label;
        private final NumenPlayer her;
        private final BlockPos origin;
        private final long startTick;
        private final long startNanos = System.nanoTime();
        ToolRun walk;
        private Trip trip;
        private Object seen;
        private int ticks;
        private int waiting;
        private int searches;
        private double first = Double.NaN;
        /** 到这一段起点为止,已经定下要付的代价:走完的步,加上装进去还没走完的路线。 */
        private double committed;
        private boolean summarised;

        Probe(String label, NumenPlayer her, BlockPos origin) {
            this.label = label;
            this.her = her;
            this.origin = origin;
            this.startTick = her.level().getGameTime();
        }

        private String rel(BlockPos pos) {
            BlockPos r = pos.subtract(origin);
            return r.getX() + "," + r.getY() + "," + r.getZ();
        }

        void sample() {
            if (walk == null || walk.done()) {
                return;
            }
            ticks++;
            Trip current = Trip.current(her);
            if (current == null) {
                return;
            }
            trip = current;
            if (current.waiting()) {
                waiting++;
            }
            Object navigation = field(current, "navigation");
            Object driver = navigation == null ? null : field(navigation, "driver");
            Object result = driver == null ? null : field(driver, "lastResult");
            if (result == null || result == seen) {
                return;
            }
            seen = result;
            SearchResult r = (SearchResult) result;
            Search search = (Search) field(driver, "lastSearch");
            Object allowance = field(driver, "allowance");
            double h = r.startEstimate();
            if (searches == 0) {
                first = h;
            }
            Route route = r.route();
            SearchResult.Blocking b = r.blocking();
            String blocking = b == null || r.reached() ? "-"
                    : b.move() + " 从 " + rel(b.from()) + " 卡在 " + rel(b.fails().cell()) + " 前提=" + b.fails().reason();
            // 从脚下搜的,起点就是身体此刻的位置:之前装进去的路线没走完的部分不算数,只算走完的步
            if (String.valueOf(field(driver, "purpose")).equals("FROM_BODY")) {
                committed = walked();
            }
            double bound = committed + h;
            committed += route == null ? 0 : route.cost();
            Constants.LOG.info(String.format(Locale.ROOT,
                    "[detour-probe] %s #%d 刻 %d 墙钟 %dms 起点 %s %s 停因 %s 展开 %d 界 %s 起点估价 %.1f(直线估价的 %.2f 倍) 总价下界 %.1f(直线估价的 %.2f 倍) 路线 %s 总预算 %s/%s 离目标最近处被拒:%s",
                    label, ++searches, her.level().getGameTime() - startTick, (System.nanoTime() - startNanos) / 1_000_000,
                    rel(search.start()), field(driver, "purpose"), r.stop(), r.expanded(),
                    Double.isNaN(r.gap()) ? "-" : String.format(Locale.ROOT, "%.2f", r.gap()), h, h / first, bound, bound / first,
                    route == null ? "无" : String.format(Locale.ROOT, "%d步 代价%.1f 终点%s", route.legs().size(), route.cost(),
                            rel(route.end())),
                    field(allowance, "spent"), field(allowance, "total"), blocking));
        }

        /** 到此刻走完的步按规划算的代价之和。 */
        private double walked() {
            double sum = 0;
            for (Report report : trip.reports()) {
                sum += report.journal().routeCost();
            }
            return sum;
        }

        void summarise(ToolRun run) {
            if (summarised) {
                return;
            }
            summarised = true;
            int dug = 0;
            int placed = 0;
            double cost = 0;
            int replans = 0;
            int blockages = 0;
            for (Report report : trip.reports()) {
                for (EditLedger.Entry e : report.ledger().entries()) {
                    if (e instanceof EditLedger.Dug) {
                        dug++;
                    } else if (e instanceof EditLedger.Placed) {
                        placed++;
                    }
                }
                cost += report.journal().routeCost();
                replans += report.journal().replans();
                blockages += report.journal().blockages();
            }
            JsonElement returned = run.data().get("returned");
            Constants.LOG.info(String.format(Locale.ROOT,
                    "[detour-probe] %s 收场 共 %d 段搜索 游戏刻 %d(其中干等搜索 %d) 墙钟 %.1fs 挖 %d 放 %d 走完的步按规划算 %.1f 刻(直线估价 %.1f,%.2f 倍) 重搜 %d 走不下去 %d 身体 %s 结局 %s 回执 %s 回值 %s",
                    label, searches, her.level().getGameTime() - startTick, waiting, (System.nanoTime() - startNanos) / 1e9,
                    dug, placed, cost, first, cost / first, replans, blockages, rel(her.blockPosition()),
                    run.succeeded() ? "到达" : "失败", run.outcome(), returned));
        }

        private static Object field(Object target, String name) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field f = type.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException e) {
                    // 往父类找
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            throw new IllegalStateException(target.getClass() + " 没有字段 " + name);
        }
    }
}
