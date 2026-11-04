package com.dwinovo.numen.gametest;

import static com.dwinovo.numen.gametest.GameTestKit.*;

import java.util.Random;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 鞘翅:{@code numen.move.glide} 从脚本的入口进,场地用真实的地形(树林、树下、丘陵,不是平地加人造障碍)。滑翔进入目标 16 格就降落,落点只求安全,
 * 落地就是这一趟的结束:回执里有落地的那一格与离目标的水平距离,不走最后一截。覆盖:树林边与树下不落在树冠上、丘陵落在平地上、没进目标范围就落了地(离目标超过
 * 96 格再起飞,不到 96 格收工)、降落途中落点被占换一个、只收一列、创造模式下滑翔、烟花不够与没鞘翅当场拒绝、被树叶卡住收工。
 * 滑翔的物理与起降的细节由寻路模块自己的用例({@code GlideGameTests} 等)守。
 */
public class ElytraGameTests {

    private static final String BATCH = "numen_elytra";
    /**
     * 又长又宽的空场地(200 × 64 × 64):滑翔要爬到巡航高度、再飞一百多格,降落点上空盘旋的圈半径十四格。GameTest 默认给场地盖一层屏障的屋顶,
     * 巡航高度读的是沿线最高的碰撞面,会被屋顶顶住,所以每条用例都开 {@code skyAccess}(去掉屋顶);四周的屏障墙还在,场地要比圈宽。
     */
    private static final String FIELD = "fly_field";
    /** 飞行的中线:场地的正中。 */
    private static final int MID = 32;
    /** 进入目标这么多格才降;降下来落在哪由地面定,落点离目标水平不超过它再加这么多(盘旋的圈半径 14 加落点那一圈 6 再留余量)。 */
    private static final double DESCENT = 16;
    private static final double GLIDE_ERROR = 24;
    /** 落地之后身体还会滑一小段(按住潜行直到水平速度消失):停稳的地方离落地的那一格不超过这么远。 */
    private static final double SLIDE = 6;

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    // ==================== 场地 ====================

    /** 整段程序返回的那个值(表);失败时是错误值里带的那份账。 */
    private static JsonObject account(ToolRun run) {
        JsonObject data = run.data();
        JsonElement value = data.get("returned");
        if (value != null && value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        JsonElement error = data.get("error");
        if (error != null && error.isJsonObject() && error.getAsJsonObject().get("data") instanceof JsonObject account) {
            return account;
        }
        return new JsonObject();
    }

    /** 地面:{@code thickness} 层,下面是石头,最上一层是草(站在上面脚在 rel y = thickness + 1)。 */
    private static void ground(GameTestHelper helper, int thickness) {
        ServerLevel level = helper.getLevel();
        var box = helper.getBounds();
        for (int x = 0; x < (int) box.getXsize(); x++) {
            for (int z = 0; z < (int) box.getZsize(); z++) {
                for (int y = 1; y <= thickness; y++) {
                    level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), (y == thickness ? Blocks.GRASS_BLOCK : Blocks.STONE).defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * 丘陵:一列的高度是几组正弦的和(起伏 {@code amplitude} 格、坡度不过三成,缓坡里有平的山顶与谷底),石头垫底、泥土、最上一层草。高度函数交回给用例,
     * 好在断言里知道一列的地面在哪。
     */
    private static java.util.function.IntBinaryOperator hills(GameTestHelper helper, int amplitude) {
        ServerLevel level = helper.getLevel();
        var box = helper.getBounds();
        java.util.function.IntBinaryOperator height = (x, z) -> (int) Math.round(amplitude
                * (2 + Math.sin(x / 30.0) + Math.sin(z / 22.0 + 1) + Math.sin((x + z) / 40.0)) / 2);
        for (int x = 0; x < (int) box.getXsize(); x++) {
            for (int z = 0; z < (int) box.getZsize(); z++) {
                int top = 1 + height.applyAsInt(x, z);
                for (int y = 1; y <= top; y++) {
                    Block block = y == top ? Blocks.GRASS_BLOCK : y >= top - 2 ? Blocks.DIRT : Blocks.STONE;
                    level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), block.defaultBlockState(), 2);
                }
            }
        }
        return height;
    }

    /** 一棵橡树(原版的样子):树干 {@code trunk} 格,顶上两层五乘五(去掉四角)、两层三乘三的树叶。树叶不会凋落。 */
    private static void tree(GameTestHelper helper, int x, int ground, int z, int trunk) {
        ServerLevel level = helper.getLevel();
        BlockState leaf = naturalLeaves().setValue(LeavesBlock.PERSISTENT, true);
        for (int y = 1; y <= trunk; y++) {
            level.setBlock(helper.absolutePos(new BlockPos(x, ground + y, z)), Blocks.OAK_LOG.defaultBlockState(), 2);
        }
        int top = ground + trunk;
        for (int dy = -2; dy <= 1; dy++) {
            int radius = dy <= -1 ? 2 : 1;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius;
                    BlockPos at = helper.absolutePos(new BlockPos(x + dx, top + dy, z + dz));
                    if (!(corner && (radius == 2 || dy == 1)) && level.getBlockState(at).isAir()) {
                        level.setBlock(at, leaf, 2);
                    }
                }
            }
        }
    }

    /** 树林:{@code x0..x1}、{@code z0..z1} 里每隔 {@code spacing} 格一棵树(位置与高度有点随机),{@code clearing} 那一块留空。 */
    private static void forest(GameTestHelper helper, int ground, int x0, int z0, int x1, int z1, int spacing, long seed,
                               int[] clearing) {
        Random random = new Random(seed);
        for (int x = x0; x <= x1; x += spacing) {
            for (int z = z0; z <= z1; z += spacing) {
                int tx = x + random.nextInt(2);
                int tz = z + random.nextInt(2);
                boolean cleared = clearing != null && tx >= clearing[0] - 2 && tx <= clearing[1] + 2 && tz >= clearing[2] - 2 && tz <= clearing[3] + 2;
                int trunk = 5 + random.nextInt(2);
                if (!cleared) {
                    tree(helper, tx, ground, tz, trunk);
                }
            }
        }
    }

    /** 穿着鞘翅、背着 {@code rockets} 支烟花的她。 */
    private static NumenPlayer winged(GameTestHelper helper, String name, BlockPos rel, int rockets, boolean creative) {
        NumenPlayer her = spawnBare(helper, name, rel, creative);
        her.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        if (rockets > 0) {
            her.getInventory().add(new ItemStack(Items.FIREWORK_ROCKET, rockets));
        }
        return her;
    }

    private static void fill(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
        ServerLevel level = helper.getLevel();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), block.defaultBlockState(), 2);
                }
            }
        }
    }

    /** 途中身体最少时剩几点血(原版会回血,只看收场时的血量看不出摔没摔)。 */
    private static float[] watchHealth(GameTestHelper helper, NumenPlayer her) {
        float[] lowest = {Float.MAX_VALUE};
        helper.onEachTick(() -> lowest[0] = Math.min(lowest[0], her.getHealth()));
        return lowest;
    }

    /** 账上最后一段滑翔落地的那一格。 */
    private static BlockPos landedAt(JsonArray glided) {
        JsonObject landed = glided.get(glided.size() - 1).getAsJsonObject().getAsJsonObject("landed_at");
        return new BlockPos(landed.get("x").getAsInt(), landed.get("y").getAsInt(), landed.get("z").getAsInt());
    }

    /** 落地的一格:脚下是天然的地面(草、泥土、石头),不是树叶、不在液体里。 */
    private static void assertOnNaturalGround(GameTestHelper helper, ServerLevel level, BlockPos cell) {
        BlockState below = level.getBlockState(cell.below());
        helper.assertTrue(below.is(BlockTags.DIRT) || below.is(Blocks.STONE), "she came down on " + below.getBlock() + " at " + cell.toShortString());
        helper.assertTrue(level.getFluidState(cell).isEmpty(), "she came down in liquid at " + cell.toShortString());
    }

    /**
     * 一趟滑翔成功收场时共同的几条:落地了就是到了(账上一段滑翔、不再起飞、没有再走一截,她就站在落地的格上)、回执里有落地格与离目标的水平距离、
     * 离目标不超过 16 格加滑翔误差、一路满血。
     */
    private static void assertGlidedAndEnded(GameTestHelper helper, ToolRun glide, NumenPlayer her, float[] lowest, BlockPos target) {
        helper.assertTrue(glide.done(), "the glide has not finished");
        helper.assertTrue(glide.succeeded(), "she did not make it: " + glide.outcome());
        JsonObject account = account(glide);
        JsonArray glided = account.getAsJsonArray("glided");
        helper.assertTrue(glided.size() == 1 && account.getAsJsonArray("took_off_at").size() == 1, "the account should hold one glide and one take-off: " + account);
        BlockPos landed = landedAt(glided);
        double apart = Math.hypot(landed.getX() - target.getX(), landed.getZ() - target.getZ());
        helper.assertTrue(apart <= DESCENT + GLIDE_ERROR, "she came down " + apart + " blocks from the place, more than " + DESCENT + " + " + GLIDE_ERROR);
        helper.assertTrue(Math.abs(account.get("distance_left").getAsDouble() - apart) <= SLIDE, "the receipt should say how far from the place she is: " + account);
        helper.assertTrue(glide.outcome().contains("landed at") && glide.outcome().contains("blocks (horizontally) from"), glide.outcome());
        helper.assertTrue(Math.hypot(her.getX() - landed.getX(), her.getZ() - landed.getZ()) <= SLIDE, "she walked on after landing: " + her.blockPosition().toShortString() + " vs " + landed.toShortString());
        helper.assertTrue(!her.isFallFlying() && !her.getAbilities().flying, "she should end on the ground");
        helper.assertTrue(lowest[0] >= 20, "she lost health on the way: " + lowest[0]);
    }

    // ==================== 真实地形 ====================

    /**
     * 目标在树林边:一片树林(树干五六格、树冠连成一片)在飞行线上,目标在树林东边的草地上,树林最后一排离它几格。落点只认天然的地面:不落在树冠上(树叶不算),
     * 也不掉血;落地就是到了。
     */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_glide_to_the_edge_of_a_grove_lands_in_the_open(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        forest(helper, 2, 96, 6, 130, 58, 3, 11, null);
        NumenPlayer her = winged(helper, "gametest_grove", new BlockPos(6, 3, MID), 14, false);
        BlockPos target = helper.absolutePos(new BlockPos(140, 3, MID));
        float[] lowest = watchHealth(helper, her);
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            assertGlidedAndEnded(helper, glide, her, lowest, target);
            assertOnNaturalGround(helper, level, landedAt(account(glide).getAsJsonArray("glided")));
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /**
     * 目标在树下:一片三十格宽的树林(树冠连成一片),目标那一列在树林中间,头顶是树冠。进了目标 16 格她正在树林西缘的上空:树冠上、树冠下都不是落点(最高碰撞面是树叶),
     * 她落在树林西边的草地上(或别处的空地),不在树冠上、不掉血;落点离目标不超过 16 格加滑翔误差。
     */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_glide_to_a_column_under_the_trees_lands_in_the_open(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        forest(helper, 2, 118, 4, 150, 60, 3, 23, null);
        NumenPlayer her = winged(helper, "gametest_under", new BlockPos(6, 3, MID), 14, false);
        BlockPos target = helper.absolutePos(new BlockPos(134, 3, MID));
        float[] lowest = watchHealth(helper, her);
        ToolRun glide = lua(her, "return numen.move.glide({x = " + target.getX() + ", z = " + target.getZ() + "})");

        succeedWhen(helper, () -> {
            assertGlidedAndEnded(helper, glide, her, lowest, target);
            BlockPos landed = landedAt(account(glide).getAsJsonArray("glided"));
            assertOnNaturalGround(helper, level, landed);
            helper.assertTrue(level.getHeight(Heightmap.Types.MOTION_BLOCKING, landed.getX(), landed.getZ()) <= landed.getY(),
                    "she came down under something at " + landed.toShortString());
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 丘陵:缓坡起伏,目标在远处的坡上。落点站得住:脚下天然、周围八列的地面与它差不到两格(不在边沿上),落地不掉血。 */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_glide_over_hills_lands_on_level_ground_not_on_an_edge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        java.util.function.IntBinaryOperator height = hills(helper, 5);
        NumenPlayer her = winged(helper, "gametest_hills", new BlockPos(6, 2 + height.applyAsInt(6, MID), MID), 14, false);
        BlockPos target = helper.absolutePos(new BlockPos(150, 2 + height.applyAsInt(150, MID), MID));
        float[] lowest = watchHealth(helper, her);
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            assertGlidedAndEnded(helper, glide, her, lowest, target);
            BlockPos landed = landedAt(account(glide).getAsJsonArray("glided"));
            assertOnNaturalGround(helper, level, landed);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, landed.getX() + dx, landed.getZ() + dz);
                    helper.assertTrue(Math.abs(top - landed.getY()) <= 1, "she came down on an edge at " + landed.toShortString() + ": the ground "
                            + dx + "," + dz + " beside it is at " + top);
                }
            }
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 起飞点在树林边:她站在树林西边,树冠比头顶高,正东几格外就是树冠;绕出树林或抬得更陡起飞,飞到目标附近落在空地上,账上一段飞了八十多格的滑翔,没掉血。 */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_glide_takes_off_beside_a_grove(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        forest(helper, 2, 10, 20, 24, 44, 3, 5, null);
        NumenPlayer her = winged(helper, "gametest_takeoff", new BlockPos(6, 3, MID), 12, false);
        BlockPos target = helper.absolutePos(new BlockPos(135, 3, MID));
        float[] lowest = watchHealth(helper, her);
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            assertGlidedAndEnded(helper, glide, her, lowest, target);
            helper.assertTrue(account(glide).getAsJsonArray("glided").get(0).getAsJsonObject().get("blocks").getAsDouble() > 80, "the glide should be long: " + account(glide));
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 中距离翻山:一道二十二格高、十一格厚的山脊横在路上,巡航高度取山脊最高处再加十六格,飞过去,进目标 16 格降落。 */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_glide_goes_over_a_ridge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        fill(helper, 50, 3, 0, 60, 24, 63, Blocks.STONE);
        NumenPlayer her = winged(helper, "gametest_ridge", new BlockPos(5, 3, MID), 16, false);
        BlockPos target = helper.absolutePos(new BlockPos(125, 3, MID));
        float[] lowest = watchHealth(helper, her);
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            assertGlidedAndEnded(helper, glide, her, lowest, target);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 目标在水边:一片池塘,目标是池塘中间。落点不在液体里(落在池边的岸上),落地就到了。 */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_glide_to_a_pond_lands_on_the_shore_not_in_the_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 4);
        fill(helper, 110, 3, 20, 130, 4, 44, Blocks.WATER);
        NumenPlayer her = winged(helper, "gametest_pond", new BlockPos(5, 5, MID), 14, false);
        BlockPos target = helper.absolutePos(new BlockPos(120, 4, MID));
        float[] lowest = watchHealth(helper, her);
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(glide.done() && glide.succeeded(), "she did not make it: " + glide.outcome());
            BlockPos landed = landedAt(account(glide).getAsJsonArray("glided"));
            helper.assertTrue(level.getFluidState(landed).isEmpty() && level.getFluidState(landed.above()).isEmpty() && level.getFluidState(landed.below()).isEmpty(),
                    "she came down in liquid at " + landed.toShortString());
            helper.assertTrue(lowest[0] >= 20, "she lost health: " + lowest[0]);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    // ==================== 没进目标范围就落了地 ====================

    /**
     * 迫降之后再起飞:飞出去不久前方一百格外砌起一堵二百五十格高的墙(烟花爬不过去),滑翔就近迫降在墙前,墙在她落地之前撤掉;落地时离目标还有一百多格(超过 96)、
     * 烟花够,就从那里再起飞,飞到目标附近降落。账上两段滑翔,第一段是迫降。
     */
    @GameTest(template = FIELD, timeoutTicks = 4000, batch = BATCH, skyAccess = true)
    public static void a_glide_that_lands_short_and_far_takes_off_again(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = winged(helper, "gametest_again", new BlockPos(5, 3, MID), 24, false);
        BlockPos target = helper.absolutePos(new BlockPos(195, 3, MID));
        float[] lowest = watchHealth(helper, her);
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(target) + ")");
        int[] flying = {0};
        int[] built = {-1};
        int[] ticks = {0};
        int[] wallX = {0};

        helper.onEachTick(() -> {
            ticks[0]++;
            if (built[0] < 0 && her.isFallFlying() && ++flying[0] == 20) {
                wallX[0] = (int) (her.getX() - helper.absolutePos(BlockPos.ZERO).getX()) + 60;
                fill(helper, wallX[0], 3, 0, wallX[0] + 1, 250, 63, Blocks.STONE);
                built[0] = ticks[0];
            }
            if (built[0] >= 0 && ticks[0] == built[0] + 12) {
                fill(helper, wallX[0], 3, 0, wallX[0] + 1, 250, 63, Blocks.AIR);
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(glide.done(), "the glide has not finished");
            helper.assertTrue(glide.succeeded(), "she did not get there: " + glide.outcome());
            JsonArray glided = account(glide).getAsJsonArray("glided");
            helper.assertTrue(glided.size() >= 2, "the account should hold two glides: " + glided);
            helper.assertTrue(glided.get(0).getAsJsonObject().get("emergency").getAsBoolean(), "the first glide should have come down early: " + glided.get(0));
            BlockPos landed = landedAt(glided);
            helper.assertTrue(Math.hypot(landed.getX() - target.getX(), landed.getZ() - target.getZ()) <= DESCENT + GLIDE_ERROR, "she did not come down near the place: " + landed);
            helper.assertTrue(lowest[0] >= 20, "she lost health: " + lowest[0]);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /**
     * 迫降之后离目标不到 96 格:前方砌起一堵二百五十格高的墙再不撤,就近迫降在墙前;离目标只有几十格,收工,不再起飞、不改成走路。回执说明为什么停、离目标还有多远,
     * 账上那一段是迫降、没掉血。
     */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_glide_that_lands_short_and_near_stops_and_says_how_far(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = winged(helper, "gametest_short", new BlockPos(5, 3, MID), 24, false);
        BlockPos target = helper.absolutePos(new BlockPos(105, 3, MID));
        float[] lowest = watchHealth(helper, her);
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(target) + ")");
        int[] flying = {0};

        helper.onEachTick(() -> {
            if (her.isFallFlying() && flying[0]++ == 20) {
                int x = (int) (her.getX() - helper.absolutePos(BlockPos.ZERO).getX()) + 40;
                fill(helper, x, 3, 0, x + 1, 250, 63, Blocks.STONE);
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(glide.done(), "the glide has not finished");
            helper.assertTrue(!glide.succeeded() && "no_path".equals(glide.kind()), "should stop short: " + glide.outcome());
            helper.assertTrue(glide.outcome().contains("come down early") && glide.outcome().contains("blocks short")
                    && glide.outcome().contains("numen.move.walk"), glide.outcome());
            JsonArray glided = account(glide).getAsJsonArray("glided");
            helper.assertTrue(glided.size() == 1 && glided.get(0).getAsJsonObject().get("emergency").getAsBoolean(), "the one glide should be an emergency landing: " + glided);
            helper.assertTrue(lowest[0] >= 20 && her.onGround() && !her.isFallFlying(), "she should be on the ground unhurt: " + lowest[0]);
            helper.assertTrue(account(glide).get("distance_left").getAsDouble() > DESCENT, "the receipt should say how far she still is: " + account(glide));
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    // ==================== 落点 ====================

    /**
     * 降落途中选好的落点落不下了:进了目标范围、降下来的时候,她正下方那一列被人砌起一根九格高的圆石柱(柱顶不是天然方块,落点那一列也不再是最高碰撞面);
     * 换一个安全的落点,不落在柱子上、不掉血。
     */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_landing_spot_taken_on_the_way_down_is_swapped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = winged(helper, "gametest_taken", new BlockPos(5, 3, MID), 14, false);
        BlockPos target = helper.absolutePos(new BlockPos(130, 3, MID));
        float[] lowest = watchHealth(helper, her);
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(target) + ")");
        BlockPos[] pillar = {null};

        helper.onEachTick(() -> {
            if (pillar[0] == null && her.isFallFlying() && Math.hypot(her.getX() - target.getX(), her.getZ() - target.getZ()) < DESCENT - 6) {
                pillar[0] = BlockPos.containing(her.getX(), 0, her.getZ());
                for (int y = 0; y <= 8; y++) {
                    level.setBlock(new BlockPos(pillar[0].getX(), helper.absolutePos(BlockPos.ZERO).getY() + 3 + y, pillar[0].getZ()), Blocks.COBBLESTONE.defaultBlockState(), 2);
                }
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(pillar[0] != null, "the pillar was never built");
            assertGlidedAndEnded(helper, glide, her, lowest, target);
            BlockPos landed = landedAt(account(glide).getAsJsonArray("glided"));
            helper.assertTrue(landed.getX() != pillar[0].getX() || landed.getZ() != pillar[0].getZ(), "she came down on the pillar");
            assertOnNaturalGround(helper, level, landed);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 只收一列:给了带 y 的格子,按它所在的那一列处理(y 不参与);只给高度没有列,当场拒绝并说要一列。 */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_glide_takes_a_column_and_ignores_the_height_of_a_cell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = winged(helper, "gametest_column", new BlockPos(5, 3, MID), 14, false);
        BlockPos target = helper.absolutePos(new BlockPos(130, 3, MID));
        float[] lowest = watchHealth(helper, her);
        ToolRun refused = lua(her, "return numen.move.glide({y = 64})");
        ToolRun[] glide = new ToolRun[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(refused.done(), "the call has not come back"))
                .thenExecute(() -> {
                    helper.assertTrue(!refused.succeeded() && refused.outcome().contains("column"), "a height alone should be refused as having no column: " + refused.outcome());
                    glide[0] = lua(her, "return numen.move.glide({x = " + target.getX() + ", y = 200, z = " + target.getZ() + "})");
                })
                .thenWaitUntil(() -> helper.assertTrue(glide[0].done(), "the glide has not finished"))
                .thenExecute(() -> {
                    assertGlidedAndEnded(helper, glide[0], her, lowest, target);
                    CompanionFactory.despawn(level.getServer(), her);
                })
                .thenSucceed();
    }

    // ==================== 拒绝与创造模式 ====================

    /** 烟花低于下限(只有 4 支)、平地上没有边沿可滑:当场拒绝,说烟花不够(有 4 支,要 5 支)与怎么补,不受理、她没动。 */
    @GameTest(template = FIELD, timeoutTicks = 200, batch = BATCH, skyAccess = true)
    public static void too_few_rockets_are_refused_at_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = winged(helper, "gametest_few", new BlockPos(5, 3, MID), 4, false);
        BlockPos start = her.blockPosition();
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(helper.absolutePos(new BlockPos(120, 3, MID))) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(glide.done(), "the call has not come back");
            helper.assertTrue(!glide.succeeded() && "no_material".equals(glide.kind()), "should be refused as missing material: " + glide.outcome());
            helper.assertTrue(glide.outcome().contains("have 4") || glide.outcome().contains("I have 4"), glide.outcome());
            helper.assertTrue(glide.outcome().contains("at least 5") && glide.outcome().contains("numen.move.walk"), glide.outcome());
            helper.assertTrue(her.blockPosition().equals(start), "she moved though the glide was refused");
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 没穿鞘翅(身上有烟花)也不是许飞的身体:当场拒绝,说没有可用的鞘翅,教她穿上。 */
    @GameTest(template = FIELD, timeoutTicks = 200, batch = BATCH, skyAccess = true)
    public static void gliding_without_an_elytra_is_refused_at_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = spawnBare(helper, "gametest_bare", new BlockPos(5, 3, MID), false);
        her.getInventory().add(new ItemStack(Items.FIREWORK_ROCKET, 8));
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(helper.absolutePos(new BlockPos(120, 3, MID))) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(glide.done(), "the call has not come back");
            helper.assertTrue(!glide.succeeded() && "no_material".equals(glide.kind()), "should be refused as missing material: " + glide.outcome());
            helper.assertTrue(glide.outcome().contains("elytra") && glide.outcome().contains("numen.gear.wear"), glide.outcome());
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 目标就在 16 格里:当场拒绝,说滑翔已经在降落的范围里,走过去就是。 */
    @GameTest(template = FIELD, timeoutTicks = 200, batch = BATCH, skyAccess = true)
    public static void a_place_inside_the_descent_range_is_refused_at_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = winged(helper, "gametest_near", new BlockPos(5, 3, MID), 14, false);
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(helper.absolutePos(new BlockPos(15, 3, MID))) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(glide.done(), "the call has not come back");
            helper.assertTrue(!glide.succeeded() && glide.outcome().contains("numen.move.walk") && glide.outcome().contains("16 blocks"), glide.outcome());
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 创造模式下叫滑翔,照样滑翔(不是创造飞行):账上有一段滑翔,收场时不在飞。 */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_creative_body_glides_when_told_to_glide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = winged(helper, "gametest_creative_glide", new BlockPos(5, 3, MID), 14, true);
        BlockPos target = helper.absolutePos(new BlockPos(130, 3, MID));
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(target) + ")");
        boolean[] flew = {false};

        helper.onEachTick(() -> flew[0] |= her.getAbilities().flying);
        succeedWhen(helper, () -> {
            helper.assertTrue(glide.done() && glide.succeeded(), "she did not make it: " + glide.outcome());
            helper.assertTrue(account(glide).getAsJsonArray("glided").size() >= 1, "the account should hold a glide: " + account(glide));
            helper.assertTrue(!flew[0] && !her.getAbilities().flying, "a glide must never use creative flight");
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    // ==================== 被方块顶住 ====================

    /**
     * 爬到一半头顶多出树冠:起飞点那根柱子是空的,可她爬到一半,头顶三格处多出一大片树叶(二十一格见方),烟花把她往上推、她顶在树叶下面。线头的柱子不在每刻查线的范围里,
     * 控制器每一组推演都撞树叶:被围住就改降(迫降),不是顶着树叶一直放烟花;这一趟在有限的刻数里有了结局(落地后再起飞或收工),账上第一段是迫降、烟花只用了几支、没掉血。
     */
    @GameTest(template = FIELD, timeoutTicks = 1500, batch = BATCH, skyAccess = true)
    public static void a_climb_into_a_canopy_comes_down_instead_of_firing_on(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = winged(helper, "gametest_canopy", new BlockPos(30, 3, MID), 24, false);
        BlockPos target = helper.absolutePos(new BlockPos(180, 3, MID));
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(target) + ")");
        BlockState leaf = naturalLeaves().setValue(LeavesBlock.PERSISTENT, true);
        float[] lowest = watchHealth(helper, her);
        int[] flying = {0};
        int[] ticks = {0};
        int[] roofAt = {-1};
        int[] doneAt = {-1};

        helper.onEachTick(() -> {
            ticks[0]++;
            if (roofAt[0] < 0 && her.isFallFlying() && ++flying[0] == 8) {
                int y = her.getBlockY() + 3;
                for (int dx = -10; dx <= 10; dx++) {
                    for (int dz = -10; dz <= 10; dz++) {
                        level.setBlock(new BlockPos(her.getBlockX() + dx, y, her.getBlockZ() + dz), leaf, 2);
                    }
                }
                roofAt[0] = ticks[0];
            }
            if (doneAt[0] < 0 && glide.done()) {
                doneAt[0] = ticks[0];
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(roofAt[0] >= 0, "the canopy was never put up");
            helper.assertTrue(glide.done(), "the glide has not come back");
            helper.assertTrue(doneAt[0] - roofAt[0] <= 1200, "it took " + (doneAt[0] - roofAt[0]) + " ticks to come to an end");
            JsonArray glided = account(glide).getAsJsonArray("glided");
            helper.assertTrue(glided.size() >= 1 && glided.get(0).getAsJsonObject().get("emergency").getAsBoolean(), "the first glide should have come down early: " + account(glide));
            helper.assertTrue(glided.get(0).getAsJsonObject().get("rockets").getAsInt() <= 6, "she kept firing rockets into the leaves: " + glided.get(0));
            helper.assertTrue(lowest[0] >= 20, "she lost health: " + lowest[0]);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /**
     * 滑翔中被树叶困住:她飞出去之后,身边多出一团实心的树叶(五乘五乘五,只留她那一格):头顶、四周、脚下都是树叶,烟花推着她顶在上面一动不动
     * (用不受重力来代替"推力与撞上去的反力正好抵消":身体悬在原地)。滑翔每刻问的是身体离开原地没有(走路卡住的同一个看门狗):一秒不动就收工,不再放烟花,
     * 回执写明顶着她的那一格与方块、放了几支烟花,不替她挖;这一趟没有再起飞。
     */
    @GameTest(template = FIELD, timeoutTicks = 1500, batch = BATCH, skyAccess = true)
    public static void a_glide_held_by_leaves_stops_and_says_what_holds_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = winged(helper, "gametest_held", new BlockPos(5, 3, MID), 24, false);
        BlockPos target = helper.absolutePos(new BlockPos(150, 3, MID));
        ToolRun glide = lua(her, "return numen.move.glide(" + xyz(target) + ")");
        BlockState leaf = naturalLeaves().setValue(LeavesBlock.PERSISTENT, true);
        int[] flying = {0};
        int[] ticks = {0};
        int[] boxedAt = {-1};
        int[] doneAt = {-1};

        helper.onEachTick(() -> {
            ticks[0]++;
            if (boxedAt[0] < 0 && her.isFallFlying() && ++flying[0] == 60) {
                BlockPos at = her.blockPosition();
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            if (dx != 0 || dy != 0 || dz != 0) {
                                level.setBlock(at.offset(dx, dy, dz), leaf, 2);
                            }
                        }
                    }
                }
                her.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                her.setNoGravity(true);
                her.setDeltaMovement(0, 0, 0);
                boxedAt[0] = ticks[0];
            }
            if (boxedAt[0] >= 0 && doneAt[0] < 0) {
                her.setDeltaMovement(0, 0, 0);
                if (glide.done()) {
                    doneAt[0] = ticks[0];
                    her.setNoGravity(false);
                }
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(boxedAt[0] >= 0, "she was never boxed in");
            helper.assertTrue(glide.done(), "the glide has not come back, she is still held");
            helper.assertTrue(!glide.succeeded(), "a held glide is not a success: " + glide.outcome());
            helper.assertTrue(doneAt[0] - boxedAt[0] <= 100, "it took " + (doneAt[0] - boxedAt[0]) + " ticks to give up");
            helper.assertTrue(glide.outcome().contains("oak_leaves") && glide.outcome().contains("held me"), glide.outcome());
            JsonArray glided = account(glide).getAsJsonArray("glided");
            helper.assertTrue(glided.size() == 1 && glided.get(0).getAsJsonObject().get("stuck").getAsBoolean(), "the account should say she got stuck, once: " + account(glide));
            helper.assertTrue(glided.get(0).getAsJsonObject().get("rockets").getAsInt() <= 8, "she kept firing rockets into the leaves: " + glided.get(0));
            CompanionFactory.despawn(level.getServer(), her);
        });
    }
}
