package com.dwinovo.numen.gametest;

import static com.dwinovo.numen.gametest.GameTestKit.*;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
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
 * 走与创造飞行:{@code numen.move.walk} 从不飞(创造飞行许飞也一样,身体在飞先落地),{@code numen.move.fly} 只用创造飞行(不许飞的当场拒绝,不碰鞘翅)。
 * 都从脚本的入口进:创造下走不飞而飞才飞、飞到半空一格悬停、不许飞当场拒绝、在飞时走先落地。鞘翅的滑翔是 {@code numen.move.glide},用例在 {@code ElytraGameTests}。
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

    /**
     * 不许飞的身体(生存)叫了创造飞行:当场拒绝,说清楚不许飞,下一步是走;穿着鞘翅的再给滑翔。她没动。
     */
    @GameTest(template = FIELD, timeoutTicks = 200, batch = BATCH, skyAccess = true)
    public static void flying_without_permission_is_refused_at_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 1);
        NumenPlayer bare = spawnBare(helper, "gametest_nofly", new BlockPos(5, 2, MID), false);
        NumenPlayer winged = winged(helper, "gametest_nofly_wings", new BlockPos(5, 2, MID + 4), 8);
        BlockPos start = bare.blockPosition();
        String place = xyz(helper.absolutePos(new BlockPos(40, 2, MID)));
        ToolRun plain = lua(bare, "return numen.move.fly(" + place + ")");
        ToolRun wings = lua(winged, "return numen.move.fly(" + place + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(plain.done() && wings.done(), "the calls have not come back");
            helper.assertTrue(!plain.succeeded() && "no_path".equals(plain.kind()), "should be refused: " + plain.outcome());
            helper.assertTrue(plain.outcome().contains("not allowed to fly") && plain.outcome().contains("numen.move.walk")
                    && !plain.outcome().contains("numen.move.glide"), plain.outcome());
            helper.assertTrue(wings.outcome().contains("not allowed to fly") && wings.outcome().contains("numen.move.glide"), wings.outcome());
            helper.assertTrue(bare.blockPosition().equals(start), "she moved though the flight was refused");
            CompanionFactory.despawn(level.getServer(), bare);
            CompanionFactory.despawn(level.getServer(), winged);
        });
    }

    /** 创造模式:飞到半空的一格,悬停在那里(不落地、没有滑翔):回执说了她还在飞,位置就在那一格里。 */
    @GameTest(template = FIELD, timeoutTicks = 600, batch = BATCH, skyAccess = true)
    public static void a_fly_to_a_cell_in_mid_air_hovers_there(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 1);
        NumenPlayer her = spawnBare(helper, "gametest_hover", new BlockPos(8, 2, MID), true);
        BlockPos hover = helper.absolutePos(new BlockPos(14, 7, MID));
        ToolRun fly = lua(her, "return numen.move.fly(" + xyz(hover) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(fly.done() && fly.succeeded(), "the fly did not come back as a success: " + fly.outcome());
            helper.assertTrue(her.getAbilities().flying && !her.onGround() && !her.isFallFlying(), "she should hover");
            helper.assertTrue(her.blockPosition().equals(hover), "she should hover in the cell: " + her.blockPosition());
            helper.assertTrue(returned(fly).get("flying").getAsBoolean() && returned(fly).getAsJsonArray("glided").size() == 0, "the account should say she is still flying: " + returned(fly));
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
