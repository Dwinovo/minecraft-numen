package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;
import static com.dwinovo.numen.pathing.gametest.UpDownGameTests.pool;

import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;

/** 流体与危险:涉水、游泳、岩浆、仙人掌火浆果岩浆块。 */
public class FluidGameTests {

    private static final String BATCH = "pathing_fluids";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 一池一格深的浅水挡在路上:踩着池底蹚过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 432)
    public static void wades_through_shallow_water(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        pool(t, 6, 0, 10, 39, 1);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(13, 1, 5)), RouteSpec.defaults()).arrives();
    }

    /** 一片三格深的水挡在路上:游过去,上岸。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 507)
    public static void swims_across_deep_water(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        pool(t, 6, 0, 13, 39, 3);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(16, 1, 5)), RouteSpec.defaults()).within(497).arrives();
    }

    /**
     * 三格深的水接着一溜一格深的浅水:游过深水,到了浅水里脚下是水底。规划认为浅水格是踩在水底上(站着),原版水里不按键每刻只沉
     * 0.005 格,身子浮在水底之上老远,落不到水底上——游的控制器要到了列中心就按住潜行沉下去,段状态机才认得站在了落点上。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void swims_from_deep_water_onto_a_shallow_bed(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(5, -4, 0, 23, -1, 39, Blocks.STONE);
        t.fill(6, -2, 0, 13, 0, 39, Blocks.WATER);
        t.fill(14, 0, 0, 22, 0, 39, Blocks.WATER);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(25, 1, 5)), RouteSpec.defaults()).within(590).arrives().then(r -> {
            if (r.report.journal().blockages() > 0) {
                throw new GameTestAssertException("游过去的路上走不通过:" + r.report.journal().replans());
            }
        });
    }

    // ==================== 流体(普查补充) ====================

    /** 一口四格深、一格宽的水井,身体在井底:游上来,上岸。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 318)
    public static void swims_up_from_the_bottom_of_a_deep_well(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(5, -4, 4, 7, -1, 6, Blocks.STONE);
        t.fill(6, -3, 5, 6, 0, 5, Blocks.WATER);
        TestBody body = t.body(6, -3, 5);
        t.go(body, Goals.at(t.at(10, 1, 5)), RouteSpec.defaults()).arrives();
    }

    /** 路上一个一格的水坑,走过去短暂落进水里又出来:照常走过去,不在水坑边反复停下重搜,一路在推进。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 312)
    public static void a_brief_dip_in_water_does_not_stall(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        pool(t, 8, 5, 8, 5, 1);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(14, 1, 5)), RouteSpec.defaults()).within(120)
                .during(r -> {
                    if (r.ticks > 5 && !r.navigation.progressing()) {
                        throw new GameTestAssertException("在水坑边停住了(第 " + r.ticks + " 刻)");
                    }
                })
                .arrives();
    }

    /** 穿着冰霜行者的靴子:三格深的一片静水挡在路上,踩着冻住的水面走过去,一次也没下水。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 315)
    public static void walks_over_water_in_frost_walker_boots(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        pool(t, 6, 0, 11, 39, 3);
        TestBody body = t.body(3, 1, 5);
        ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
        boots.enchant(t.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FROST_WALKER), 2);
        body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, boots);
        t.go(body, Goals.at(t.at(14, 1, 5)), RouteSpec.defaults())
                // 冰霜行者冻住的水面是原版在身体经过时自己冻的,过一阵又化回水,不是导航动的手
                .passive((before, now) -> before.is(Blocks.WATER) && now.is(Blocks.FROSTED_ICE))
                .during(r -> {
                    if (r.body.isInWater()) {
                        throw new GameTestAssertException("下了水");
                    }
                })
                .arrives();
    }

    // ==================== 危险 ====================

    /** 一条两旁摆着仙人掌、火、甜浆果丛、脚下夹着岩浆块的路:不碰它们,一点血不掉。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 339)
    public static void passes_cactus_fire_berries_and_magma_unhurt(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(5, 1, 3, 14, 1, 3, Blocks.SAND);
        t.fill(5, 1, 7, 14, 1, 7, Blocks.SAND);
        // 仙人掌头顶压一块石头,原版就不再往上长,世界不会在走的途中自己变
        t.set(6, 2, 3, Blocks.CACTUS);
        t.set(6, 3, 3, Blocks.STONE);
        t.set(9, 2, 3, Blocks.CACTUS);
        t.set(9, 3, 3, Blocks.STONE);
        t.set(8, 1, 7, Blocks.NETHERRACK);
        t.set(8, 2, 7, Blocks.FIRE);
        t.set(10, 1, 7, Blocks.GRASS_BLOCK);
        t.set(10, 2, 7, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
        t.set(12, 0, 5, Blocks.MAGMA_BLOCK);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(16, 1, 5)), RouteSpec.defaults()).arrives().then(UpDownGameTests::unhurt);
    }
}
