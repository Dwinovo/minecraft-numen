package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 上下:上一级、下一级、连续台阶、落差、摔落上限、跳水缓冲、挑对的下法,以及普查补充的几种。 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class UpDownGameTests {

    private static final String BATCH = "pathing_updown";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 跳上一整块。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void steps_up_one_block(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(8, 1, 3, 12, 1, 7, Blocks.STONE);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(10, 2, 5)), RouteSpec.defaults()).arrives();
    }

    /** 走下一整块。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void steps_down_one_block(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 3, 7, 1, 7, Blocks.STONE);
        TestBody body = t.body(4, 2, 5);
        t.go(body, Goals.at(t.at(11, 1, 5)), RouteSpec.defaults()).arrives().then(UpDownGameTests::unhurt);
    }

    /** 连上五级台阶。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void climbs_a_staircase_of_blocks(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        for (int i = 0; i < 5; i++) {
            t.fill(6 + i, 1, 4, 6 + i, 1 + i, 6, Blocks.STONE);
        }
        t.fill(11, 1, 4, 13, 5, 6, Blocks.STONE);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(12, 6, 5)), RouteSpec.defaults()).arrives();
    }

    /** 从两格高处落下,不掉血。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void drops_two_blocks(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 3, 6, 2, 7, Blocks.STONE);
        TestBody body = t.body(4, 3, 5);
        t.go(body, Goals.at(t.at(10, 1, 5)), RouteSpec.defaults()).arrives().then(UpDownGameTests::unhurt);
    }

    /** 从三格高处落下,原版摔不疼的高度。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void drops_three_blocks(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 3, 6, 3, 7, Blocks.STONE);
        TestBody body = t.body(4, 4, 5);
        t.go(body, Goals.at(t.at(10, 1, 5)), RouteSpec.defaults()).arrives().then(UpDownGameTests::unhurt);
    }

    /** 六格高的台子,旁边有一道长台阶:不跳,走台阶下去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 900)
    public static void does_not_jump_from_beyond_the_fall_limit(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 2, 7, 6, 7, Blocks.STONE);
        for (int i = 0; i < 6; i++) {
            t.fill(2, 1, 8 + i, 3, 6 - i, 8 + i, Blocks.STONE);
        }
        TestBody body = t.body(6, 7, 5);
        t.go(body, Goals.at(t.at(11, 1, 5)), RouteSpec.defaults()).within(700).arrives()
                .then(UpDownGameTests::unhurt);
    }

    /** 十二格高的柱顶,旁边一池两格深的水:跳进水里,不掉血。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void jumps_into_water_to_break_a_fall(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 1, 4, 4, 11, 4, Blocks.STONE);
        pool(t, 5, 3, 7, 5, 2);
        TestBody body = t.body(4, 12, 4);
        t.go(body, Goals.at(t.at(11, 1, 4)), RouteSpec.defaults()).arrives().then(UpDownGameTests::unhurt);
    }

    /** 八格高的台子:一边是直落,一边是水池,远处有台阶——挑不摔伤的那条。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 900)
    public static void picks_a_safe_way_down(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 10, 14, 8, 14, Blocks.STONE);
        pool(t, 15, 11, 16, 13, 2);
        for (int i = 0; i < 8; i++) {
            t.fill(10 + i, 1, 20 + i, 14, 8 - i, 20 + i, Blocks.STONE);
        }
        TestBody body = t.body(12, 9, 12);
        t.go(body, Goals.at(t.at(12, 1, 3)), RouteSpec.defaults()).within(700).arrives()
                .then(UpDownGameTests::unhurt);
    }

    // ==================== 普查补充 ====================

    /**
     * 两格高的崖,身上有圆石:垫一块台阶上去。起步时身子探进了要放台阶的那一格,先退回来再放——放不进自己身体占着的格。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void places_a_step_after_moving_out_of_the_cell(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(8, 1, 2, 14, 2, 8, Blocks.STONE);
        TestBody body = t.body(6, 1, 5);
        body.moveTo(body.getX() + 0.35, body.getY(), body.getZ(), -90, 0);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(10, 3, 5)), RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build())
                .arrives();
    }

    /** 下一级台阶之后,正前方是岩浆坑:落在台阶下就停住转弯,不冲进去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void stops_after_a_step_down_before_lava(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 4, 5, 1, 6, Blocks.STONE);
        t.fill(7, -1, 3, 9, -1, 7, Blocks.STONE);
        t.fill(7, 0, 4, 8, 0, 6, Blocks.LAVA);
        TestBody body = t.body(3, 2, 5);
        net.minecraft.core.BlockPos lava = t.at(7, 0, 5);
        t.go(body, Goals.at(t.at(6, 1, 12)), RouteSpec.defaults())
                .during(r -> {
                    if (r.body.getX() >= lava.getX() - 0.3 + 0.01 && Math.abs(r.body.getZ() - lava.getZ() - 0.5) < 2) {
                        throw new GameTestAssertException("冲过了头,身体到了岩浆坑上方");
                    }
                })
                .arrives().then(UpDownGameTests::unhurt);
    }

    /** 从八格高处落进只有一格深的水:照原版,落进水里就不算摔,不掉血。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void one_deep_water_breaks_a_fall_like_vanilla(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 1, 4, 4, 7, 4, Blocks.STONE);
        pool(t, 5, 3, 6, 5, 1);
        TestBody body = t.body(4, 8, 4);
        t.go(body, Goals.at(t.at(10, 1, 4)), RouteSpec.defaults())
                .arrives().then(UpDownGameTests::unhurt);
    }

    /**
     * 规格许落二十格;七格高的台子,另有一道长台阶。满血的身体直接跳(摔掉 4 点血比绕台阶便宜);只剩 7 点血的身体
     * 摔落上限收到 4 格,走台阶,一点血不掉。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 1000)
    public static void low_health_tightens_the_fall_limit(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        RouteSpec spec = RouteSpec.defaults().edit().maxFallHeightNoWater(20).build();
        for (int side : new int[] {0, 20}) {
            t.fill(side + 2, 1, 2, side + 6, 7, 6, Blocks.STONE);
            for (int i = 0; i < 7; i++) {
                t.fill(side + 2, 1, 7 + 2 * i, side + 3, 7 - i, 8 + 2 * i, Blocks.STONE);
            }
        }
        TestBody healthy = t.body(5, 8, 4);
        TestBody weak = t.body(25, 8, 4);
        weak.setHealth(7);
        t.go(healthy, Goals.at(t.at(10, 1, 4)), spec).within(800).arrives().then(r -> {
            if (r.lowestHealth >= 20) {
                throw new GameTestAssertException("满血的身体没有直接跳下去 " + r.navigation);
            }
        });
        t.go(weak, Goals.at(t.at(30, 1, 4)), spec).within(800).arrives().then(r -> {
            if (r.lowestHealth < 7) {
                throw new GameTestAssertException("只剩 7 点血却摔了下去:最低 " + r.lowestHealth);
            }
        });
    }

    /** 下落时在空中不回身:腾空那几刻身体一直朝着离地那一刻的方向,落地之后才转向下一步。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void does_not_turn_around_in_mid_air(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 5, 6, 3, 5, Blocks.STONE);
        TestBody body = t.body(4, 4, 5);
        float[] takeoff = {Float.NaN};
        t.go(body, Goals.at(t.at(7, 1, 1)), RouteSpec.defaults())
                .during(r -> {
                    if (r.body.onGround()) {
                        takeoff[0] = Float.NaN;
                        return;
                    }
                    if (Float.isNaN(takeoff[0])) {
                        takeoff[0] = r.body.getYRot();
                    } else if (Math.abs(Mth.wrapDegrees(r.body.getYRot() - takeoff[0])) > 90) {
                        throw new GameTestAssertException("空中回了身:离地时 yaw " + takeoff[0] + ",此刻 " + r.body.getYRot());
                    }
                })
                .arrives().then(UpDownGameTests::unhurt);
    }

    static void unhurt(Trial.Run r) {
        if (r.lowestHealth < r.body.getMaxHealth()) {
            throw new GameTestAssertException("掉了血:最低 " + r.lowestHealth);
        }
    }

    /**
     * 在地板里挖一池 {@code depth} 格深的静水,池底与四周(连地板下那一层空隙)都用石头围住,水不往外流。
     */
    static void pool(Trial t, int x0, int z0, int x1, int z1, int depth) {
        t.fill(x0 - 1, -depth, z0 - 1, x1 + 1, -1, z1 + 1, Blocks.STONE);
        t.fill(x0 - 1, -depth - 1, z0 - 1, x1 + 1, -depth - 1, z1 + 1, Blocks.STONE);
        t.fill(x0, 1 - depth, z0, x1, 0, z1, Blocks.WATER);
    }
}
