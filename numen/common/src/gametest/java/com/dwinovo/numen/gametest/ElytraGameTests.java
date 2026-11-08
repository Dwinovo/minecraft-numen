package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.SkyAccess;
import static com.dwinovo.numen.gametest.GameTestKit.*;

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
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 鞘翅:{@code numen.move.glide} 从脚本的入口进,到身体、世界、回执的这一整条。场地是固定的方块(平地),每条用例不超过 600 刻:平地一段滑翔落地不掉血、
 * 回执里有落地的那一格与离目标的水平距离(落地就是这一趟的结束,不走最后一截)、只收一列、创造模式下照样滑翔、烟花不够与没鞘翅与目标太近当场拒绝。
 * 滑翔的长趟与真实地形(树林、树下、丘陵、山脊、池塘、高墙、迫降再起飞、落点被占)的决策在寻路模块的纯 JVM 场景里跑({@code GlideScenarioTest}),
 * 原版物理与推算对得上、起降的细节由寻路模块自己的用例({@code GlideGameTests})守。
 */
public class ElytraGameTests {

    private static final String BATCH = "numen_elytra";
    /**
     * 又长又宽的空场地(200 × 64 × 64):滑翔要爬到巡航高度、再飞一百多格,降落点上空盘旋的圈半径十四格。GameTest 默认给场地盖一层屏障的屋顶,
     * 巡航高度读的是沿线最高的碰撞面,会被屋顶顶住,所以每条用例都标 {@link SkyAccess}(去掉屋顶);四周的屏障墙还在,场地要比圈宽。
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

    /** 穿着鞘翅、背着 {@code rockets} 支烟花的她。 */
    private static NumenPlayer winged(GameTestHelper helper, String name, BlockPos rel, int rockets, boolean creative) {
        NumenPlayer her = spawnBare(helper, name, rel, creative);
        her.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        if (rockets > 0) {
            her.getInventory().add(new ItemStack(Items.FIREWORK_ROCKET, rockets));
        }
        return her;
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

    /** 只收一列:给了带 y 的格子,按它所在的那一列处理(y 不参与);只给高度没有列,当场拒绝并说要一列。 */
    @SkyAccess
    @GameTest(template = FIELD, timeoutTicks = 600, batch = BATCH)
    public static void a_glide_takes_a_column_and_ignores_the_height_of_a_cell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = winged(helper, "gametest_column", new BlockPos(5, 3, MID), 14, false);
        BlockPos target = helper.absolutePos(new BlockPos(50, 3, MID));
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
    @SkyAccess
    @GameTest(template = FIELD, timeoutTicks = 200, batch = BATCH)
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
    @SkyAccess
    @GameTest(template = FIELD, timeoutTicks = 200, batch = BATCH)
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
    @SkyAccess
    @GameTest(template = FIELD, timeoutTicks = 200, batch = BATCH)
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
    @SkyAccess
    @GameTest(template = FIELD, timeoutTicks = 600, batch = BATCH)
    public static void a_creative_body_glides_when_told_to_glide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 2);
        NumenPlayer her = winged(helper, "gametest_creative_glide", new BlockPos(5, 3, MID), 14, true);
        BlockPos target = helper.absolutePos(new BlockPos(50, 3, MID));
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
}
