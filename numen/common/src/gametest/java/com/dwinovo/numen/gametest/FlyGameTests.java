package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.SkyAccess;
import com.dwinovo.numen.api.gametest.TestBounds;
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
import net.minecraft.world.level.block.Blocks;

/**
 * 走与创造飞行:{@code numen.move.walk} 从不飞(创造飞行许飞也一样,身体在飞先落地),{@code numen.move.fly} 只用创造飞行(不许飞的当场拒绝,不碰鞘翅)。
 * 都从脚本的入口进:飞到半空一格悬停、在飞时走先落地;不许飞的受理在 {@code FlightAdmissionTest}。鞘翅的滑翔是 {@code numen.move.glide},用例在 {@code ElytraGameTests}。
 */
public class FlyGameTests {

    private static final String BATCH = "numen_fly";
    /**
     * 又长又宽的空场地(200 × 64 × 64):滑翔要爬到巡航高度、再飞一百多格,降落点上空盘旋的圈半径十四格。GameTest 默认给场地盖一层屏障的屋顶,
     * 巡航高度读的是沿线最高的碰撞面,会被屋顶顶住,所以每条用例都标 {@link SkyAccess}(去掉屋顶);四周的屏障墙还在,场地要比圈宽。
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
        var box = TestBounds.of(helper);
        for (int x = 0; x < (int) box.getXsize(); x++) {
            for (int z = 0; z < (int) box.getZsize(); z++) {
                for (int y = 1; y <= thickness; y++) {
                    level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
    }

    /** 创造模式:飞到半空的一格,悬停在那里(不落地、没有滑翔):回执说了她还在飞,位置就在那一格里。 */
    @SkyAccess
    @GameTest(template = FIELD, timeoutTicks = 600, batch = BATCH)
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

    /** 在飞时走:创造飞行悬在半空的她叫了走,先降到地上(回执说了),再走到目标,收场时不在飞。 */
    @SkyAccess
    @GameTest(template = FIELD, timeoutTicks = 600, batch = BATCH)
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
