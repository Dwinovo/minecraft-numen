package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 带数据组件的物品经 Lua 入口放出去:同伴背着一个空潜影盒、一个装着钻石的潜影盒,放下其中一个,
 * 放下的盒子与剩在背包里的盒子加起来,钻石一颗不少。
 */
public class ItemComponentsGameTests {

    private static final int DIAMONDS = 3;

    private static ItemStack fullBox() {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
                java.util.List.of(new ItemStack(Items.DIAMOND, DIAMONDS))));
        return box;
    }

    /** 背包里所有潜影盒物品里的钻石数。 */
    private static int diamondsInBoxes(NumenPlayer companion) {
        int n = 0;
        for (ItemStack stack : companion.getInventory().items) {
            if (stack.is(Items.SHULKER_BOX)) {
                n += stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream()
                        .filter(s -> s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
            }
        }
        return n;
    }

    private static int boxesCarried(NumenPlayer companion) {
        return companion.getInventory().countItem(Items.SHULKER_BOX);
    }

    /** 放下的那个盒子里的钻石数。 */
    private static int diamondsPlaced(ServerLevel level, BlockPos at) {
        helperAssertBox(level, at);
        Container box = (Container) level.getBlockEntity(at);
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).is(Items.DIAMOND)) {
                n += box.getItem(i).getCount();
            }
        }
        return n;
    }

    private static void helperAssertBox(ServerLevel level, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof Container)) {
            throw new AssertionError("no shulker box block entity at " + at.toShortString());
        }
    }

    /** 空盒在前、满盒在后(快捷栏第 0、1 格),放一个:钻石守恒。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void use_block_places_a_shulker_box_without_losing_contents_empty_first(GameTestHelper helper) {
        placeByUseBlock(helper, "gametest_box_a", true);
    }

    /** 满盒在前、空盒在后,放一个:钻石守恒。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void use_block_places_a_shulker_box_without_losing_contents_full_first(GameTestHelper helper) {
        placeByUseBlock(helper, "gametest_box_b", false);
    }

    private static void placeByUseBlock(GameTestHelper helper, String name, boolean emptyFirst) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, name, new BlockPos(3, 2, 5), false);
        var inv = companion.getInventory();
        if (emptyFirst) {
            inv.setItem(0, new ItemStack(Items.SHULKER_BOX));
            inv.setItem(1, fullBox());
        } else {
            inv.setItem(0, fullBox());
            inv.setItem(1, new ItemStack(Items.SHULKER_BOX));
        }
        BlockPos floor = helper.absolutePos(new BlockPos(5, 1, 5));
        BlockPos placedAt = floor.above();
        ToolRun place = lua(companion, "numen.use.block(" + xyz(floor) + ", {item = \"minecraft:shulker_box\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(place.done() && place.succeeded(), "use.block did not succeed: " + place.outcome());
            helper.assertTrue(level.getBlockState(placedAt).is(Blocks.SHULKER_BOX), "no shulker box was placed");
            helper.assertTrue(boxesCarried(companion) == 1, "she should carry one box now, has " + boxesCarried(companion));
            int total = diamondsPlaced(level, placedAt) + diamondsInBoxes(companion);
            helper.assertTrue(total == DIAMONDS, "diamonds lost: placed box holds " + diamondsPlaced(level, placedAt)
                    + ", carried boxes hold " + diamondsInBoxes(companion) + " (of " + DIAMONDS + ")");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** {@code numen.build.place} 放一个潜影盒:同上,钻石守恒。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void build_place_puts_a_shulker_box_without_losing_contents_full_first(GameTestHelper helper) {
        placeByBuild(helper, "gametest_box_c", false);
    }

    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void build_place_puts_a_shulker_box_without_losing_contents_empty_first(GameTestHelper helper) {
        placeByBuild(helper, "gametest_box_d", true, "minecraft:shulker_box", new BlockPos(5, 2, 5));
    }

    /** 悬空的一格:没有可点的面,手放做不到,走直接写。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void build_place_puts_a_floating_shulker_box_without_losing_contents(GameTestHelper helper) {
        placeByBuild(helper, "gametest_box_e", false, "minecraft:shulker_box", new BlockPos(4, 4, 5));
    }

    /** 朝向是地板点不出来的(朝北):手放放不出这个状态,走直接写。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void build_place_puts_a_sideways_shulker_box_without_losing_contents(GameTestHelper helper) {
        placeByBuild(helper, "gametest_box_f", false, "minecraft:shulker_box[facing=north]", new BlockPos(5, 2, 5));
    }

    private static void placeByBuild(GameTestHelper helper, String name, boolean emptyFirst) {
        placeByBuild(helper, name, emptyFirst, "minecraft:shulker_box", new BlockPos(5, 2, 5));
    }

    private static void placeByBuild(GameTestHelper helper, String name, boolean emptyFirst, String block,
                                     BlockPos rel) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, name, new BlockPos(3, 2, 5), false);
        var inv = companion.getInventory();
        if (emptyFirst) {
            inv.setItem(0, new ItemStack(Items.SHULKER_BOX));
            inv.setItem(1, fullBox());
        } else {
            inv.setItem(0, fullBox());
            inv.setItem(1, new ItemStack(Items.SHULKER_BOX));
        }
        BlockPos placedAt = helper.absolutePos(rel);
        ToolRun place = lua(companion,
                "numen.build.place({{name = \"" + block + "\", pos = " + xyz(placedAt) + "}})");

        succeedWhen(helper, () -> {
            helper.assertTrue(place.done() && place.succeeded(), "build.place did not succeed: " + place.outcome());
            helper.assertTrue(level.getBlockState(placedAt).is(Blocks.SHULKER_BOX), "no shulker box was placed");
            helper.assertTrue(boxesCarried(companion) == 1, "she should carry one box now, has " + boxesCarried(companion));
            int total = diamondsPlaced(level, placedAt) + diamondsInBoxes(companion);
            helper.assertTrue(total == DIAMONDS, "diamonds lost: placed box holds " + diamondsPlaced(level, placedAt)
                    + ", carried boxes hold " + diamondsInBoxes(companion) + " (of " + DIAMONDS + ")");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
