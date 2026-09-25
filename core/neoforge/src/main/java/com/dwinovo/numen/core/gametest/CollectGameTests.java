package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 捡东西:{@code collect_items} 走过去把地上的掉落物捡起来,可以只捡点名的那几种。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class CollectGameTests {

    /** 捡东西批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_collect")
    public static void prepareCollectBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 点名只捡铁锭:三块铁锭捡回来,旁边那堆圆石原样躺在地上。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_collect")
    public static void collect_items_picks_up_only_the_named_items(GameTestHelper helper) {
        dropOnFloor(helper, new BlockPos(10, 2, 4), Items.IRON_INGOT, 3);
        dropOnFloor(helper, new BlockPos(4, 2, 11), Items.COBBLESTONE, 4);
        NumenPlayer companion = spawnAt(helper, "gametest_gleaner", new BlockPos(2, 2, 2), false);
        ToolRun collect = call(companion, "collect_items", args("item_ids", List.of("minecraft:iron_ingot")));

        helper.succeedWhen(() -> {
            helper.assertTrue(collect.done(), "collect_items has not finished");
            helper.assertTrue(collect.succeeded(), "collect_items failed: " + collect.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.IRON_INGOT) == 3,
                    "the iron was not picked up");
            helper.assertTrue(companion.getInventory().countItem(Items.COBBLESTONE) == 0
                            && onFloor(helper, Items.COBBLESTONE) == 4,
                    "the cobblestone was not left where it lay");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 不点名就全捡:散在两处的铁锭和圆石都到了身上,地上一件不剩。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_collect")
    public static void collect_items_without_names_picks_up_everything(GameTestHelper helper) {
        dropOnFloor(helper, new BlockPos(10, 2, 4), Items.IRON_INGOT, 3);
        dropOnFloor(helper, new BlockPos(4, 2, 11), Items.COBBLESTONE, 4);
        NumenPlayer companion = spawnAt(helper, "gametest_sweeper", new BlockPos(2, 2, 2), false);
        ToolRun collect = call(companion, "collect_items", args());

        helper.succeedWhen(() -> {
            helper.assertTrue(collect.done(), "collect_items has not finished");
            helper.assertTrue(collect.succeeded(), "collect_items failed: " + collect.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.IRON_INGOT) == 3
                            && companion.getInventory().countItem(Items.COBBLESTONE) == 4,
                    "not everything was picked up");
            helper.assertTrue(onFloor(helper, Items.IRON_INGOT) + onFloor(helper, Items.COBBLESTONE) == 0,
                    "something is still on the floor");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 回执报的是到手的件数,不是捡了几堆:一堆三块、一堆两块铁锭,说的是五块。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_collect")
    public static void collect_items_reports_items_not_stacks(GameTestHelper helper) {
        dropOnFloor(helper, new BlockPos(9, 2, 4), Items.IRON_INGOT, 3);
        dropOnFloor(helper, new BlockPos(9, 2, 10), Items.IRON_INGOT, 2);
        NumenPlayer companion = spawnAt(helper, "gametest_tallier", new BlockPos(2, 2, 7), false);
        ToolRun collect = call(companion, "collect_items", args("item_ids", List.of("minecraft:iron_ingot")));

        helper.succeedWhen(() -> {
            helper.assertTrue(collect.done(), "collect_items has not finished");
            helper.assertTrue(companion.getInventory().countItem(Items.IRON_INGOT) == 5,
                    "not all five ingots were picked up");
            helper.assertTrue(collect.succeeded() && collect.outcome().startsWith("collected 5 "),
                    "the reply does not count five items: " + collect.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 地上什么都没有:照样收场,回执如实说一件没捡到。
     *
     * <p>半径显式给小:问的就是她脚边这片空地。场地之间隔得比默认半径远(见 {@link GameTestKit}),
     * 隔壁撒的铁锭本来也扫不进来。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_collect")
    public static void collect_items_with_nothing_on_the_ground_says_none(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_empty_handed", new BlockPos(2, 2, 7), false);
        ToolRun collect = call(companion, "collect_items", args("radius", 3));

        helper.succeedWhen(() -> {
            helper.assertTrue(collect.done(), "collect_items has not finished");
            helper.assertTrue(collect.outcome().startsWith("collected 0 "),
                    "the reply does not say nothing was picked up: " + collect.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 掉落物掉进了坑里:场地垫高两层,正中留一个两格深的坑,三块铁锭躺在坑底。她自己下到坑里捡上来,
     * 不因为站在坑沿够不着就放弃。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_collect")
    public static void collect_items_goes_down_into_a_pit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (x == 8 && z == 8) {
                    continue;
                }
                for (int y = 2; y <= 3; y++) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)), Blocks.STONE.defaultBlockState());
                }
            }
        }
        dropOnFloor(helper, new BlockPos(8, 2, 8), Items.IRON_INGOT, 3);
        NumenPlayer companion = spawnAt(helper, "gametest_spelunker", new BlockPos(3, 4, 3), false);
        ToolRun collect = call(companion, "collect_items", args("item_ids", List.of("minecraft:iron_ingot")));

        helper.succeedWhen(() -> {
            helper.assertTrue(collect.done(), "collect_items has not finished");
            helper.assertTrue(collect.succeeded() && companion.getInventory().countItem(Items.IRON_INGOT) == 3,
                    "the ingots at the bottom of the pit were not picked up: " + collect.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 在 rel 那一格的地面上放一堆 {@code count} 个 {@code item},不带初速。 */
    private static void dropOnFloor(GameTestHelper helper, BlockPos rel, Item item, int count) {
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(rel));
        ItemEntity drop = new ItemEntity(helper.getLevel(), at.x, at.y, at.z, new ItemStack(item, count));
        drop.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(drop);
    }

    /** 这块场地的地面上还躺着多少个 {@code item}。 */
    private static int onFloor(GameTestHelper helper, Item item) {
        AABB site = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(16, 8, 16);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, site, e -> e.getItem().is(item)).stream()
                .mapToInt(e -> e.getItem().getCount()).sum();
    }


    /**
     * 三块铁锭搁在一根三格高的石柱顶上,捡东西从不改地形,她够不着。照样收场,但回执得交代还有三块留在那儿、
     * 在哪——不能只说"捡了 0 块",让模型以为这一片已经干净了。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_collect")
    public static void collect_items_says_what_it_could_not_reach(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int y = 2; y <= 4; y++) {
            level.setBlockAndUpdate(helper.absolutePos(new BlockPos(10, y, 10)), Blocks.STONE.defaultBlockState());
        }
        dropOnFloor(helper, new BlockPos(10, 5, 10), Items.IRON_INGOT, 3);
        NumenPlayer companion = spawnAt(helper, "gametest_shortarm", new BlockPos(3, 2, 3), false);
        ToolRun collect = call(companion, "collect_items", args("item_ids", List.of("minecraft:iron_ingot")));

        helper.succeedWhen(() -> {
            helper.assertTrue(collect.done(), "collect_items has not finished");
            helper.assertTrue(companion.getInventory().countItem(Items.IRON_INGOT) == 0
                            && onFloor(helper, Items.IRON_INGOT) == 3,
                    "the ingots on the pillar were somehow taken");
            BlockPos top = helper.absolutePos(new BlockPos(10, 5, 10));
            helper.assertTrue(collect.outcome().contains("3 ")
                            && collect.outcome().contains(top.getX() + "," + top.getY() + "," + top.getZ()),
                    "the reply does not say three ingots were left on the pillar: " + collect.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 刚掉下来的东西有一小段拾取冷却(原版方块掉落是 10 刻):三块铁锭就落在她脚边,冷却还没过。她得等冷却过了
     * 捡起来,不能因为"走到了却没进背包"就当它捡不了。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_collect")
    public static void collect_items_waits_out_a_fresh_drops_pickup_delay(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_patient", new BlockPos(5, 2, 5), false);
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(6, 2, 5)));
        ItemEntity drop = new ItemEntity(helper.getLevel(), at.x, at.y, at.z, new ItemStack(Items.IRON_INGOT, 3));
        drop.setDeltaMovement(Vec3.ZERO);
        drop.setDefaultPickUpDelay();
        helper.getLevel().addFreshEntity(drop);
        ToolRun collect = call(companion, "collect_items", args("item_ids", List.of("minecraft:iron_ingot")));

        helper.succeedWhen(() -> {
            helper.assertTrue(collect.done(), "collect_items has not finished");
            helper.assertTrue(companion.getInventory().countItem(Items.IRON_INGOT) == 3
                            && collect.outcome().startsWith("collected 3 "),
                    "the fresh drop at her feet was not picked up: " + collect.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
