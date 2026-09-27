package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;
import static com.dwinovo.numen.pathing.gametest.UpDownGameTests.pool;

import java.util.List;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 流体与危险:涉水、游泳、流水、岩浆、细雪与蜘蛛网、仙人掌火浆果岩浆块、压力板与绊线、两只怪之间。 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class FluidGameTests {

    private static final String BATCH = "pathing_fluids";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 从不进 {@code cells} 里的任何一格(身体的脚所在的格)。 */
    private static void avoid(Trial.Run r, Trial t, List<BlockPos> cells) {
        BlockPos at = r.body.blockPosition();
        if (cells.contains(at)) {
            throw new GameTestAssertException("走进了该避开的格 " + t.rel(at));
        }
    }

    private static List<BlockPos> box(Trial t, int x0, int y0, int z0, int x1, int y1, int z1) {
        List<BlockPos> out = new java.util.ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    out.add(t.at(x, y, z));
                }
            }
        }
        return out;
    }

    /** 一池一格深的浅水挡在路上:踩着池底蹚过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void wades_through_shallow_water(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        pool(t, 6, 0, 10, 39, 1);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(13, 1, 5)), RouteSpec.defaults()).arrives();
    }

    /** 一片三格深的水挡在路上:游过去,上岸。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void swims_across_deep_water(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        pool(t, 6, 0, 13, 39, 3);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(16, 1, 5)), RouteSpec.defaults()).within(700).arrives();
    }

    /** 一道流水横在路上(一头是源头,顺着两格高的水渠流开),水渠尽头之外能绕:绕过去,脚从不踩进流水。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void keeps_out_of_flowing_water(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(7, 1, 0, 7, 2, 12, Blocks.STONE);
        t.fill(9, 1, 0, 9, 2, 12, Blocks.STONE);
        t.set(8, 1, 0, Blocks.WATER);
        TestBody body = t.body(4, 1, 4);
        t.go(body, Goals.at(t.at(12, 1, 4)), RouteSpec.defaults()).within(700)
                .during(r -> {
                    if (r.body.level().getFluidState(r.body.blockPosition()).is(net.minecraft.tags.FluidTags.WATER)) {
                        throw new GameTestAssertException("脚踩进了流水");
                    }
                })
                .arrives();
    }

    /**
     * 一片横贯场地的岩浆:不许改地形时走不过去,身体一点不碰;诊断说得出"许改地形(在岩浆上搭桥)才有路"。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void refuses_to_cross_lava(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(7, -1, 0, 11, -1, 39, Blocks.STONE);
        t.fill(8, 0, 0, 10, 0, 39, Blocks.LAVA);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(14, 1, 5)), RouteSpec.defaults()).fails(Outcome.NeedsAlter.class)
                .then(UpDownGameTests::unhurt);
    }

    /** 直路贴着一溜岩浆走,旁边两格远有一条稍长的路:走离岩浆远的那条,一次也不贴着它。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void keeps_away_from_lava_at_a_small_detour(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, -1, 3, 14, -1, 5, Blocks.STONE);
        t.fill(5, 0, 4, 13, 0, 4, Blocks.LAVA);
        TestBody body = t.body(3, 1, 5);
        List<BlockPos> beside = box(t, 5, 1, 5, 13, 1, 5);
        t.go(body, Goals.at(t.at(15, 1, 5)), RouteSpec.defaults())
                .during(r -> avoid(r, t, beside))
                .arrives().then(UpDownGameTests::unhurt);
    }

    /** 路上一片细雪、一片蜘蛛网,旁边能绕:都绕开。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void avoids_powder_snow_and_cobwebs(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(6, 0, 3, 7, 0, 7, Blocks.POWDER_SNOW);
        t.fill(6, -1, 3, 7, -1, 7, Blocks.STONE);
        t.fill(10, 1, 3, 11, 2, 7, Blocks.COBWEB);
        TestBody body = t.body(3, 1, 5);
        List<BlockPos> bad = new java.util.ArrayList<>(box(t, 6, 0, 3, 7, 1, 7));
        bad.addAll(box(t, 10, 1, 3, 11, 2, 7));
        t.go(body, Goals.at(t.at(14, 1, 5)), RouteSpec.defaults())
                .during(r -> avoid(r, t, bad))
                .arrives();
    }

    // ==================== 流体(普查补充) ====================

    /** 一口四格深、一格宽的水井,身体在井底:游上来,上岸。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void swims_up_from_the_bottom_of_a_deep_well(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(5, -4, 4, 7, -1, 6, Blocks.STONE);
        t.fill(6, -3, 5, 6, 0, 5, Blocks.WATER);
        TestBody body = t.body(6, -3, 5);
        t.go(body, Goals.at(t.at(10, 1, 5)), RouteSpec.defaults()).arrives();
    }

    /** 泡在三格深的池子里,身上有圆石、许改地形,去处在岸上:游上岸再说,不在水里垫柱。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void does_not_pillar_while_in_water(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        pool(t, 4, 3, 9, 8, 3);
        t.fill(11, 1, 3, 13, 2, 8, Blocks.STONE);
        TestBody body = t.body(6, -2, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        List<BlockPos> water = box(t, 4, -2, 3, 9, 0, 8);
        t.go(body, Goals.at(t.at(12, 3, 5)), RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build())
                .within(600).arrives().then(r -> {
                    for (var e : r.report.ledger().entries()) {
                        if (e instanceof com.dwinovo.numen.pathing.drive.EditLedger.Placed && water.contains(e.pos())) {
                            throw new GameTestAssertException("在水里放了方块 " + t.rel(e.pos()));
                        }
                    }
                });
    }

    /** 路上一个一格的水坑,走过去短暂落进水里又出来:照常走过去,不在水坑边反复停下重搜,一路在推进。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
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

    /**
     * 一道横贯场地的水渠,规格不许下水、也不许往水里放方块,许改地形、身上有圆石:先垫高一格,在水面上方那一层搭桥过去,
     * 水渠里的水一格没动。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 900)
    public static void bridges_over_water_without_placing_into_it(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        pool(t, 7, 0, 9, 39, 1);
        TestBody body = t.body(4, 1, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        RouteSpec spec = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL)
                .exclude(Semantics.Kind.WATER)
                .bans(new com.dwinovo.numen.pathing.spec.BlockBans(java.util.Set.of(), java.util.Set.of(Blocks.WATER),
                        java.util.Set.of()))
                .build();
        List<BlockPos> water = box(t, 7, 0, 0, 9, 0, 39);
        t.go(body, Goals.at(t.at(12, 1, 5)), spec).within(800).arrives().then(r -> {
            for (var e : r.report.ledger().entries()) {
                if (water.contains(e.pos())) {
                    throw new GameTestAssertException("动了水渠里的水 " + t.rel(e.pos()));
                }
            }
        });
    }

    /** 潜到三格深的池底某一格站着。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void dives_to_a_cell_on_the_bottom(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        pool(t, 5, 3, 10, 8, 3);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(8, -2, 6)), RouteSpec.defaults()).arrives();
    }

    /** 穿着冰霜行者的靴子:三格深的一片静水挡在路上,踩着冻住的水面走过去,一次也没下水。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void walks_over_water_in_frost_walker_boots(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        pool(t, 6, 0, 11, 39, 3);
        TestBody body = t.body(3, 1, 5);
        ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
        boots.enchant(t.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.FROST_WALKER), 2);
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
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
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

    /** 走道正中一块压力板,旁边能绕:出厂规格绕开,压力板一直没被踩下。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void steps_around_a_pressure_plate_by_default(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(8, 1, 5, Blocks.STONE_PRESSURE_PLATE);
        TestBody body = t.body(3, 1, 5);
        BlockPos plate = t.at(8, 1, 5);
        t.go(body, Goals.at(t.at(13, 1, 5)), RouteSpec.defaults())
                .during(r -> {
                    if (t.level.getBlockState(plate).getValue(PressurePlateBlock.POWERED)) {
                        throw new GameTestAssertException("踩了压力板");
                    }
                })
                .arrives();
    }

    /** 走道上一道绊线,旁边能绕:与压力板一样,出厂规格绕开;规格放开机关时直接跨过去也不绕。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void treats_tripwire_like_pressure_plates(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(8, 1, 3, 8, 1, 7, Blocks.TRIPWIRE);
        t.fill(28, 1, 3, 28, 1, 7, Blocks.TRIPWIRE);
        List<BlockPos> wire = box(t, 8, 1, 3, 8, 1, 7);
        t.go(t.body(3, 1, 5), Goals.at(t.at(13, 1, 5)), RouteSpec.defaults())
                .during(r -> avoid(r, t, wire))
                .arrives();
        RouteSpec allowed = RouteSpec.defaults().edit().allow(Semantics.Kind.TRIGGER).build();
        long[] maxZ = {0};
        t.go(t.body(23, 1, 5), Goals.at(t.at(33, 1, 5)), allowed)
                .during(r -> maxZ[0] = Math.max(maxZ[0], Math.abs(r.body.blockPosition().getZ() - t.at(0, 0, 5).getZ())))
                .arrives().then(r -> {
                    if (maxZ[0] > 1) {
                        throw new GameTestAssertException("放开了机关还绕路");
                    }
                });
    }

    /** 两只怪一左一右,中间的缝正对着去处:不从它们中间直穿,绕开它们的危险半径。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void does_not_walk_between_two_monsters(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        BlockPos a = t.at(10, 1, 17);
        BlockPos b = t.at(10, 1, 23);
        t.threats = () -> List.of(new Threat(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 4),
                new Threat(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 4));
        TestBody body = t.body(3, 1, 20);
        t.go(body, Goals.at(t.at(17, 1, 20)), RouteSpec.defaults()).within(700)
                .during(r -> {
                    BlockPos p = r.body.blockPosition().subtract(t.origin);
                    if (p.getX() >= 9 && p.getX() <= 11 && p.getZ() >= 18 && p.getZ() <= 22) {
                        throw new GameTestAssertException("从两只怪中间直穿过去了");
                    }
                })
                .arrives();
    }
}
