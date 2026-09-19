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

    /** 地上什么都没有:照样收场,回执如实说一件没捡到。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_collect")
    public static void collect_items_with_nothing_on_the_ground_says_none(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_empty_handed", new BlockPos(2, 2, 7), false);
        ToolRun collect = call(companion, "collect_items", args());

        helper.succeedWhen(() -> {
            helper.assertTrue(collect.done(), "collect_items has not finished");
            helper.assertTrue(collect.outcome().startsWith("collected 0 "),
                    "the reply does not say nothing was picked up: " + collect.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
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
}
