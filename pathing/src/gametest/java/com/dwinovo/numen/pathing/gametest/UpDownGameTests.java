package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
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

    /** 落在什么上:和平难度每秒回一点血,看摔掉几点血的用例在这一批里关掉自然回血,批后照原样还回去。 */
    private static final String LANDING = "pathing_landing";

    private static boolean regeneration;

    @BeforeBatch(batch = LANDING)
    public static void settleLanding(ServerLevel level) {
        Worlds.settle(level);
        GameRules.BooleanValue rule = level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION);
        regeneration = rule.get();
        rule.set(false, level.getServer());
    }

    @AfterBatch(batch = LANDING)
    public static void restoreLanding(ServerLevel level) {
        level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(regeneration, level.getServer());
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

    /**
     * 规格许落二十格,满血的身体站在九格高的石柱顶上。一边柱脚四周是石头地:落九格掉 6 点血,跳下去;另一边柱脚四周一圈
     * 朝上的滴水石锥尖:落上去按原版掉 15 点,摔完不到 6 点血,不跳,不许改地形就没有路,一点血不掉。
     */
    @GameTest(template = ARENA, batch = LANDING, timeoutTicks = 800)
    public static void will_not_fall_onto_an_upward_stalagmite(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        RouteSpec spec = RouteSpec.defaults().edit().maxFallHeightNoWater(20).build();
        t.fill(22, 1, 2, 26, 1, 6, Blocks.POINTED_DRIPSTONE);
        for (int side : new int[] {0, 19}) {
            t.fill(side + 4, 1, 3, side + 6, 9, 5, Blocks.STONE);
        }
        TestBody stone = t.body(5, 10, 4);
        TestBody spikes = t.body(24, 10, 4);
        t.go(stone, Goals.at(t.at(10, 1, 4)), spec).within(600).arrives().then(r -> {
            if (r.lowestHealth >= 20) {
                throw new GameTestAssertException("落在石头上摔得起,却没有跳下去 " + r.navigation);
            }
        });
        t.go(spikes, Goals.at(t.at(29, 1, 4)), spec).within(600)
                .fails(com.dwinovo.numen.pathing.api.Outcome.NeedsAlter.class).then(UpDownGameTests::unhurt);
    }

    /**
     * 只剩 8 点血的身体站在八格高的石柱顶上,规格许落二十格。落在石头上掉 5 点,摔完不到 6 点血,不跳;落在干草块上按原版
     * 只掉 1 点,跳下去。
     */
    @GameTest(template = ARENA, batch = LANDING, timeoutTicks = 800)
    public static void hay_takes_a_higher_fall_than_stone(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        RouteSpec spec = RouteSpec.defaults().edit().maxFallHeightNoWater(20).build();
        t.fill(22, 0, 1, 28, 0, 7, Blocks.HAY_BLOCK);
        for (int side : new int[] {0, 19}) {
            t.fill(side + 4, 1, 3, side + 6, 8, 5, Blocks.STONE);
        }
        TestBody stone = t.body(5, 9, 4);
        TestBody hay = t.body(24, 9, 4);
        stone.setHealth(8);
        hay.setHealth(8);
        t.go(stone, Goals.at(t.at(8, 1, 4)), spec).within(600)
                .fails(com.dwinovo.numen.pathing.api.Outcome.NeedsAlter.class).then(r -> {
                    if (r.lowestHealth < 8) {
                        throw new GameTestAssertException("落在石头上摔不起,却摔了下去:最低 " + r.lowestHealth);
                    }
                });
        t.go(hay, Goals.at(t.at(27, 1, 4)), spec).within(600).arrives().then(r -> {
            if (r.lowestHealth != 7) {
                throw new GameTestAssertException("落在干草块上应当只掉 1 点血:最低 " + r.lowestHealth);
            }
        });
    }

    /** 下落时在空中不回身:腾空那几刻身体一直朝着离地那一刻的方向,落地之后才转向下一步。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void does_not_turn_around_in_mid_air(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 5, 6, 3, 5, Blocks.STONE);
        TestBody body = t.body(4, 4, 5);
        t.go(body, Goals.at(t.at(7, 1, 1)), RouteSpec.defaults())
                .during(Scenes.noTurnInMidAir())
                .arrives().then(UpDownGameTests::unhurt);
    }

    /**
     * 十六格高的柱顶,四周都是硬地,背包里有一桶水,许改自然地形:走出边沿之前才把水桶拿到手上,落到够得着地面时倒水,
     * 落进水里一点血不掉,再把水收回桶里;账上先有倒下的水、后有收回。另一具身体从三格高处走下来,背包里也有水桶——
     * 这一跳摔不疼,不备水桶。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void catches_a_high_fall_with_a_water_bucket(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 1, 4, 4, 15, 4, Blocks.STONE);
        TestBody body = t.body(4, 16, 4);
        body.getInventory().setItem(5, new ItemStack(Items.WATER_BUCKET));
        RouteSpec natural = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build();
        t.go(body, Goals.at(t.at(12, 1, 4)), natural).within(500).arrives().then(r -> {
            unhurt(r);
            var entries = r.report.ledger().entries();
            boolean poured = entries.stream().anyMatch(e -> e instanceof com.dwinovo.numen.pathing.drive.EditLedger.Placed p
                    && p.after().is(Blocks.WATER));
            boolean scooped = entries.stream().anyMatch(e -> e instanceof com.dwinovo.numen.pathing.drive.EditLedger.Placed p
                    && p.before().is(Blocks.WATER) && p.after().isAir());
            if (!poured || !scooped) {
                throw new GameTestAssertException("水没倒下或没收回:" + entries);
            }
            if (!r.body.getInventory().contains(new ItemStack(Items.WATER_BUCKET))) {
                throw new GameTestAssertException("水没收回桶里");
            }
        });
        t.fill(20, 1, 4, 22, 3, 6, Blocks.STONE);
        TestBody low = t.body(21, 4, 5);
        low.getInventory().setItem(5, new ItemStack(Items.WATER_BUCKET));
        t.go(low, Goals.at(t.at(27, 1, 5)), natural).arrives().then(r -> {
            boolean held = r.report.actions().stream().anyMatch(a -> a instanceof com.dwinovo.numen.pathing.body.BodyAction.Held h
                    && h.item() == Items.WATER_BUCKET);
            if (held) {
                throw new GameTestAssertException("摔不疼的一跳也备了水桶");
            }
        });
    }

    /**
     * 十六格高的柱顶,去处就是柱脚紧挨着的那一格(高落差的落点),背包里有一桶水,许改自然地形:走出边沿、把水倒在去处那一格里
     * 接住自己,一点血不掉,收回水,到达。目标格保护不许往要站的格里放方块,倒下又当场收回的水不在其列。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void catches_a_high_fall_right_in_the_goal_cell(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 1, 4, 4, 15, 4, Blocks.STONE);
        TestBody body = t.body(4, 16, 4);
        body.getInventory().setItem(5, new ItemStack(Items.WATER_BUCKET));
        RouteSpec natural = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build();
        var goal = t.at(5, 1, 4);
        t.go(body, Goals.at(goal), natural).within(500).arrives().then(r -> {
            unhurt(r);
            var entries = r.report.ledger().entries();
            boolean poured = entries.stream().anyMatch(e -> e instanceof com.dwinovo.numen.pathing.drive.EditLedger.Placed p
                    && p.pos().equals(goal) && p.after().is(Blocks.WATER));
            boolean scooped = entries.stream().anyMatch(e -> e instanceof com.dwinovo.numen.pathing.drive.EditLedger.Placed p
                    && p.pos().equals(goal) && p.before().is(Blocks.WATER) && p.after().isAir());
            if (!poured || !scooped) {
                throw new GameTestAssertException("水应当倒在去处那一格、再收回:" + entries);
            }
        });
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
