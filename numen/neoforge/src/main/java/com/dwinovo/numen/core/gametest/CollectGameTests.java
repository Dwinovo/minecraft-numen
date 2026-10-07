package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
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

/**
 * 捡东西:库里的 {@code numen.work.collect} 扫地上的掉落物、一件件走过去,原版玩家走近就捡起来。它返回走过去捡掉的件数;走不到的
 * 那一步(寻路)失败,整段如实停在那里。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class CollectGameTests {

    /** 捡东西批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_collect")
    public static void prepareCollectBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 程序跑完了、跑到了最后,返回的是 {@code n}。 */
    private static boolean returned(ToolRun run, int n) {
        return run.ranToTheEnd() && run.receipt().contains("\\nreturned: " + n);
    }

    /** 散在两处的铁锭和圆石都到了身上,地上一件不剩;返回的是件数,不是几堆。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_collect")
    public static void work_collect_picks_up_everything_around_her(GameTestHelper helper) {
        dropOnFloor(helper, new BlockPos(7, 2, 4), Items.IRON_INGOT, 3);
        dropOnFloor(helper, new BlockPos(4, 2, 7), Items.COBBLESTONE, 4);
        NumenPlayer companion = spawnAt(helper, "gametest_sweeper", new BlockPos(2, 2, 2), false);
        ToolRun collect = lua(companion, "return numen.work.collect()");

        succeedWhen(helper, () -> {
            helper.assertTrue(collect.receipt() != null, "numen.work.collect has not finished");
            helper.assertTrue(returned(collect, 7), "numen.work.collect did not count seven items: " + collect.receipt());
            helper.assertTrue(companion.getInventory().countItem(Items.IRON_INGOT) == 3
                            && companion.getInventory().countItem(Items.COBBLESTONE) == 4,
                    "not everything was picked up");
            helper.assertTrue(onFloor(helper, Items.IRON_INGOT) + onFloor(helper, Items.COBBLESTONE) == 0,
                    "something is still on the floor");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 返回的是到手的件数,不是捡了几堆:一堆三块、一堆两块铁锭,返回五。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_collect")
    public static void work_collect_counts_items_not_stacks(GameTestHelper helper) {
        dropOnFloor(helper, new BlockPos(6, 2, 4), Items.IRON_INGOT, 3);
        dropOnFloor(helper, new BlockPos(6, 2, 10), Items.IRON_INGOT, 2);
        NumenPlayer companion = spawnAt(helper, "gametest_tallier", new BlockPos(2, 2, 7), false);
        ToolRun collect = lua(companion, "return numen.work.collect()");

        succeedWhen(helper, () -> {
            helper.assertTrue(collect.receipt() != null, "numen.work.collect has not finished");
            helper.assertTrue(companion.getInventory().countItem(Items.IRON_INGOT) == 5,
                    "not all five ingots were picked up");
            helper.assertTrue(returned(collect, 5), "it does not count five items: " + collect.receipt());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 地上什么都没有:一件都不走,看一眼就跑完,返回 0——扫过了、没有要捡的。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_collect")
    public static void work_collect_with_nothing_on_the_ground_returns_none(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_empty_handed", new BlockPos(2, 2, 7), false);
        ToolRun collect = lua(companion, "return numen.work.collect()");

        succeedWhen(helper, () -> {
            helper.assertTrue(collect.receipt() != null, "numen.work.collect has not finished");
            helper.assertTrue(returned(collect, 0) && collect.receipt().contains("ok · 1 call · "),
                    "a sweep with nothing on the ground did more than one look: " + collect.receipt());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 掉落物掉进了坑里:场地垫高两层,正中留一个两格深的坑,三块铁锭躺在坑底。她自己下到坑里捡上来,
     * 不因为站在坑沿够不着就放弃。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_collect")
    public static void work_collect_goes_down_into_a_pit(GameTestHelper helper) {
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
        NumenPlayer companion = spawnAt(helper, "gametest_spelunker", new BlockPos(5, 4, 5), false);
        ToolRun collect = lua(companion, "return numen.work.collect()");

        succeedWhen(helper, () -> {
            helper.assertTrue(collect.receipt() != null, "numen.work.collect has not finished");
            helper.assertTrue(returned(collect, 3) && companion.getInventory().countItem(Items.IRON_INGOT) == 3,
                    "the ingots at the bottom of the pit were not picked up: " + collect.receipt());
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
     * 三块铁锭搁在一根三格高的石柱顶上,不改地形的路走不上去:走过去的那一步(规划路线)失败,整段如实停在那里,回执说是哪一步、
     * 为什么;铁锭原样留在柱顶——不能让模型以为这一片已经干净了。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_collect")
    public static void work_collect_stops_where_it_cannot_walk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int y = 2; y <= 4; y++) {
            level.setBlockAndUpdate(helper.absolutePos(new BlockPos(10, y, 10)), Blocks.STONE.defaultBlockState());
        }
        dropOnFloor(helper, new BlockPos(10, 5, 10), Items.IRON_INGOT, 3);
        NumenPlayer companion = spawnAt(helper, "gametest_shortarm", new BlockPos(6, 2, 6), false);
        ToolRun collect = lua(companion, "return numen.work.collect()");

        succeedWhen(helper, () -> {
            helper.assertTrue(collect.receipt() != null, "numen.work.collect has not finished");
            helper.assertTrue(!collect.ranToTheEnd() && collect.receipt().contains("numen.move.go: "),
                    "a sweep that can reach nothing did not stop at the walk: " + collect.receipt());
            helper.assertTrue(companion.getInventory().countItem(Items.IRON_INGOT) == 0
                            && onFloor(helper, Items.IRON_INGOT) == 3,
                    "the ingots on the pillar were somehow taken: she holds "
                            + companion.getInventory().countItem(Items.IRON_INGOT) + ", the floor has "
                            + onFloor(helper, Items.IRON_INGOT) + "; " + collect.receipt());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 刚掉下来的东西有一小段拾取冷却(原版方块掉落是 10 刻):三块铁锭就落在她脚边,冷却还没过。它还在冷却,她就再走上去一次,
     * 直到捡起来——不能因为"走到了却没进背包"就当它捡不了。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_collect")
    public static void work_collect_waits_out_a_fresh_drops_pickup_delay(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_patient", new BlockPos(5, 2, 5), false);
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(6, 2, 5)));
        ItemEntity drop = new ItemEntity(helper.getLevel(), at.x, at.y, at.z, new ItemStack(Items.IRON_INGOT, 3));
        drop.setDeltaMovement(Vec3.ZERO);
        drop.setDefaultPickUpDelay();
        helper.getLevel().addFreshEntity(drop);
        ToolRun collect = lua(companion, "return numen.work.collect()");

        succeedWhen(helper, () -> {
            helper.assertTrue(collect.receipt() != null, "numen.work.collect has not finished");
            helper.assertTrue(companion.getInventory().countItem(Items.IRON_INGOT) == 3 && returned(collect, 3),
                    "the fresh drop at her feet was not picked up: " + collect.receipt());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
