package com.dwinovo.numen.gametest;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 走与飞:{@code numen.move.walk} 从不飞(创造飞行许飞也一样,身体在飞先落地),{@code numen.move.fly} 只飞(许飞走创造飞行,否则穿着鞘翅滑翔)。
 * 都从脚本的入口进:树林边起飞、中距离翻山、第一次滑翔中途落地后再起飞、目标在水边不落水、烟花低于下限与没鞘翅当场拒绝、创造下走不飞而飞飞、
 * 在飞时走先落地。滑翔的物理与起降的细节由寻路模块自己的用例({@code GlideGameTests} 等)守。
 */
public class FlyGameTests {

    private static final String BATCH = "numen_fly";
    /**
     * 又长又宽的空场地(200 × 64 × 64):滑翔要爬到巡航高度、再飞一百多格,降落点上空盘旋的圈半径十四格。GameTest 默认给场地盖一层屏障的屋顶,
     * 巡航高度读的是沿线最高的碰撞面,会被屋顶顶住,所以每条用例都开 {@code skyAccess}(去掉屋顶);四周的屏障墙还在,场地要比圈宽。
     */
    private static final String FIELD = "fly_field";
    /** 飞行的中线:场地的正中。 */
    private static final int MID = 32;

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 整段程序返回的那个值(表)。 */
    private static JsonObject returned(ToolRun run) {
        JsonElement value = run.data().get("returned");
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    /** 整个场地铺 {@code thickness} 层石头地板(从 rel y = 1 起,站在上面脚在 rel y = 1 + thickness)。 */
    private static void floor(GameTestHelper helper, int thickness) {
        ServerLevel level = helper.getLevel();
        var box = helper.getBounds();
        for (int x = 0; x < (int) box.getXsize(); x++) {
            for (int z = 0; z < (int) box.getZsize(); z++) {
                for (int y = 1; y <= thickness; y++) {
                    level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
    }

    /** 穿着鞘翅、背着 {@code rockets} 支烟花的她。 */
    private static NumenPlayer winged(GameTestHelper helper, String name, BlockPos rel, int rockets) {
        NumenPlayer her = spawnBare(helper, name, rel, false);
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

    /** 一段滑翔的账里某个字段(数)。 */
    private static double glide(JsonArray glided, int i, String field) {
        return glided.get(i).getAsJsonObject().get(field).getAsDouble();
    }

    /**
     * 树林边起飞:她站在一片树林的西边,树冠比她的头顶高,正东方向几格外就是树冠,目标在树林另一头的一百多格外。起飞点要在附近铺开了找(绕出树林,
     * 或抬得更陡),滑翔照样飞到,落在目标这一侧,走完最后一截;账上有一段飞了八十多格的滑翔,没掉血。
     */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_flight_takes_off_beside_a_grove(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 1);
        for (int x = 10; x <= 22; x += 3) {
            for (int z = 20; z <= 44; z += 3) {
                fill(helper, x, 2, z, x, 7, z, Blocks.OAK_LOG);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int y = 6; y <= 9; y++) {
                            BlockPos at = helper.absolutePos(new BlockPos(x + dx, y, z + dz));
                            if (level.getBlockState(at).isAir()) {
                                level.setBlock(at, naturalLeaves().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
                            }
                        }
                    }
                }
            }
        }
        NumenPlayer her = winged(helper, "gametest_grove", new BlockPos(6, 2, MID), 12);
        BlockPos target = helper.absolutePos(new BlockPos(135, 2, MID));
        ToolRun fly = lua(her, "return numen.move.fly(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(fly.done(), "the flight has not finished");
            helper.assertTrue(fly.succeeded() && her.blockPosition().distSqr(target) <= 4, "she did not get there: " + fly.outcome());
            JsonArray glided = returned(fly).getAsJsonArray("glided");
            helper.assertTrue(glided.size() >= 1 && glide(glided, 0, "blocks") > 80 && glide(glided, 0, "health_lost") == 0,
                    "the account should hold a long glide without damage: " + glided);
            helper.assertTrue(!her.isFallFlying() && !her.getAbilities().flying, "she should end on the ground");
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 中距离翻山:一道二十二格高、十一格厚的山脊横在路上,滑翔的巡航高度取山脊最高处再加十六格,飞过去落在目标这一侧。 */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_flight_goes_over_a_ridge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 1);
        fill(helper, 50, 2, 0, 60, 23, 63, Blocks.STONE);
        NumenPlayer her = winged(helper, "gametest_ridge", new BlockPos(5, 2, MID), 16);
        BlockPos target = helper.absolutePos(new BlockPos(125, 2, MID));
        ToolRun fly = lua(her, "return numen.move.fly(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(fly.done(), "the flight has not finished");
            helper.assertTrue(fly.succeeded() && her.blockPosition().distSqr(target) <= 4, "she did not get over the ridge: " + fly.outcome());
            JsonArray glided = returned(fly).getAsJsonArray("glided");
            helper.assertTrue(glided.size() >= 1 && glide(glided, 0, "blocks") > 80 && glide(glided, 0, "health_lost") == 0,
                    "the account should hold a long glide without damage: " + glided);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /**
     * 第一次滑翔中途落地之后再起飞:飞出去不久前方一百格外砌起一堵二百五十格高的墙(烟花爬不过去),滑翔改降、迫降在墙前;墙在她落地之前撤掉,
     * 落地时烟花还够、离目标还远,就从那里再起飞,飞到目标。账上两段滑翔,第一段是迫降。
     */
    @GameTest(template = FIELD, timeoutTicks = 4000, batch = BATCH, skyAccess = true)
    public static void a_glide_that_lands_halfway_takes_off_again(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 1);
        NumenPlayer her = winged(helper, "gametest_again", new BlockPos(5, 2, MID), 24);
        BlockPos target = helper.absolutePos(new BlockPos(185, 2, MID));
        ToolRun fly = lua(her, "return numen.move.fly(" + xyz(target) + ")");
        int[] flying = {0};
        int[] built = {-1};
        int[] ticks = {0};
        int[] wallX = {0};

        helper.onEachTick(() -> {
            ticks[0]++;
            if (built[0] < 0 && her.isFallFlying() && ++flying[0] == 20) {
                wallX[0] = (int) (her.getX() - helper.absolutePos(BlockPos.ZERO).getX()) + 90;
                fill(helper, wallX[0], 2, 0, wallX[0] + 1, 250, 63, Blocks.STONE);
                built[0] = ticks[0];
            }
            if (built[0] >= 0 && ticks[0] == built[0] + 12) {
                fill(helper, wallX[0], 2, 0, wallX[0] + 1, 250, 63, Blocks.AIR);
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(fly.done(), "the flight has not finished");
            helper.assertTrue(fly.succeeded() && her.blockPosition().distSqr(target) <= 4, "she did not get there: " + fly.outcome());
            JsonArray glided = returned(fly).getAsJsonArray("glided");
            helper.assertTrue(glided.size() >= 2, "the account should hold two glides: " + glided);
            helper.assertTrue(glided.get(0).getAsJsonObject().get("emergency").getAsBoolean(),
                    "the first glide should have come down early: " + glided.get(0));
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /**
     * 目标在水边:一片池塘,目标是池塘中间的一格水。滑翔不落在水里,落在最近的岸上,再走(游)完最后一截;账上每段滑翔落地的那一格都不在液体里,到了目标。
     */
    @GameTest(template = FIELD, timeoutTicks = 3000, batch = BATCH, skyAccess = true)
    public static void a_flight_to_a_pond_lands_on_the_shore_not_in_the_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 3);
        fill(helper, 110, 2, 24, 128, 3, 40, Blocks.WATER);
        NumenPlayer her = winged(helper, "gametest_pond", new BlockPos(5, 4, MID), 12);
        BlockPos target = helper.absolutePos(new BlockPos(119, 3, MID));
        ToolRun fly = lua(her, "return numen.move.fly(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(fly.done(), "the flight has not finished");
            helper.assertTrue(fly.succeeded(), "she did not get to the pond: " + fly.outcome());
            JsonArray glided = returned(fly).getAsJsonArray("glided");
            helper.assertTrue(glided.size() >= 1, "the account should hold a glide: " + glided);
            for (JsonElement glide : glided) {
                JsonObject landed = glide.getAsJsonObject().getAsJsonObject("landed_at");
                BlockPos cell = new BlockPos(landed.get("x").getAsInt(), landed.get("y").getAsInt(), landed.get("z").getAsInt());
                helper.assertTrue(level.getFluidState(cell).isEmpty() && level.getFluidState(cell.above()).isEmpty(),
                        "a glide came down in liquid at " + cell.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 烟花低于下限(只有 4 支)、平地上没有边沿可滑:当场拒绝,说烟花不够(有 4 支,要 5 支)与怎么补,不受理、她没动。 */
    @GameTest(template = FIELD, timeoutTicks = 200, batch = BATCH, skyAccess = true)
    public static void too_few_rockets_are_refused_at_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 1);
        NumenPlayer her = winged(helper, "gametest_few", new BlockPos(5, 2, MID), 4);
        BlockPos start = her.blockPosition();
        ToolRun fly = lua(her, "return numen.move.fly(" + xyz(helper.absolutePos(new BlockPos(120, 2, MID))) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(fly.done(), "the call has not come back");
            helper.assertTrue(!fly.succeeded() && "no_material".equals(fly.kind()), "should be refused as missing material: " + fly.outcome());
            helper.assertTrue(fly.outcome().contains("have 4") || fly.outcome().contains("I have 4"), fly.outcome());
            helper.assertTrue(fly.outcome().contains("at least 5") && fly.outcome().contains("numen.move.walk"), fly.outcome());
            helper.assertTrue(her.blockPosition().equals(start), "she moved though the flight was refused");
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 没穿鞘翅(身上有烟花)也不是许飞的身体:当场拒绝,说没有可用的鞘翅,教她穿上。 */
    @GameTest(template = FIELD, timeoutTicks = 200, batch = BATCH, skyAccess = true)
    public static void flying_without_an_elytra_is_refused_at_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 1);
        NumenPlayer her = spawnBare(helper, "gametest_bare", new BlockPos(5, 2, MID), false);
        her.getInventory().add(new ItemStack(Items.FIREWORK_ROCKET, 8));
        ToolRun fly = lua(her, "return numen.move.fly(" + xyz(helper.absolutePos(new BlockPos(120, 2, MID))) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(fly.done(), "the call has not come back");
            helper.assertTrue(!fly.succeeded() && "no_material".equals(fly.kind()), "should be refused as missing material: " + fly.outcome());
            helper.assertTrue(fly.outcome().contains("elytra") && fly.outcome().contains("numen.gear.wear"), fly.outcome());
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /**
     * 创造模式下走不飞、飞才飞:一条十二格宽的岩浆河横贯场地,她身上什么料都没有。先叫走到对岸:不论走不走得到,走的一路身体都不在飞(走从不飞);
     * 再叫飞到她不在的那一岸:飞过岩浆落在那里,回执说飞了十几格、从哪里起飞。
     */
    @GameTest(template = FIELD, timeoutTicks = 1200, batch = BATCH, skyAccess = true)
    public static void in_creative_a_walk_never_flies_and_a_fly_flies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 3);
        fill(helper, 20, 2, 0, 31, 3, 63, Blocks.LAVA);
        NumenPlayer her = spawnBare(helper, "gametest_creative", new BlockPos(10, 4, MID), true);
        BlockPos near = helper.absolutePos(new BlockPos(10, 4, MID));
        BlockPos far = helper.absolutePos(new BlockPos(40, 4, MID));
        boolean[] flew = {false};
        ToolRun[] run = new ToolRun[2];
        BlockPos[] across = new BlockPos[1];

        helper.onEachTick(() -> {
            if (run[1] == null && her.getAbilities().flying) {
                flew[0] = true;
            }
        });
        steps(helper)
                .thenExecute(() -> run[0] = lua(her, "return numen.move.walk(" + xyz(far) + ")"))
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "the walk has not come back"))
                .thenExecute(() -> {
                    helper.assertTrue(!flew[0] && !her.getAbilities().flying, "a walk must never fly: " + run[0].outcome());
                    across[0] = her.getX() > helper.absolutePos(new BlockPos(26, 0, 0)).getX() ? near : far;
                    run[1] = lua(her, "return numen.move.fly(" + xyz(across[0]) + ")");
                })
                .thenWaitUntil(() -> helper.assertTrue(run[1].done(), "the fly has not come back"))
                .thenExecute(() -> {
                    helper.assertTrue(run[1].succeeded() && her.blockPosition().distSqr(across[0]) <= 4, "the fly did not get across: " + run[1].outcome());
                    helper.assertTrue(returned(run[1]).get("flown").getAsDouble() >= 12, "the account should say how far she flew: " + returned(run[1]));
                    helper.assertTrue(returned(run[1]).getAsJsonArray("took_off_at").size() >= 1, "the account should say where she took off");
                    CompanionFactory.despawn(level.getServer(), her);
                })
                .thenSucceed();
    }

    /** 在飞时走:创造飞行悬在半空的她叫了走,先降到地上(回执说了),再走到目标,收场时不在飞。 */
    @GameTest(template = FIELD, timeoutTicks = 600, batch = BATCH, skyAccess = true)
    public static void a_walk_that_starts_in_the_air_comes_down_first(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 1);
        NumenPlayer her = spawnBare(helper, "gametest_alight", new BlockPos(8, 8, MID), true);
        her.getAbilities().mayfly = true;
        her.getAbilities().flying = true;
        her.onUpdateAbilities();
        BlockPos target = helper.absolutePos(new BlockPos(20, 2, MID));
        ToolRun walk = lua(her, "return numen.move.walk(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "the walk has not finished");
            helper.assertTrue(walk.succeeded() && her.blockPosition().distSqr(target) <= 2, "she did not walk there: " + walk.outcome());
            helper.assertTrue(walk.outcome().contains("came down to the ground first"), "the account should say she came down first: " + walk.outcome());
            helper.assertTrue(!her.getAbilities().flying && her.onGround(), "she should end on the ground");
            helper.assertTrue(returned(walk).get("flown").getAsDouble() == 0, "walking is not flying: " + returned(walk));
            CompanionFactory.despawn(level.getServer(), her);
        });
    }
}
