package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 交互:{@code interact_at} 对着水面舀水、放船;{@code interact_entity} 走到活物跟前右键、左键。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class InteractGameTests {

    /** 交互批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_interact")
    public static void prepareInteractBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 桶对水右键:准星式右键的完整管线钉桩。准星射线不含流体(与原版一致),
     * 水面永远点不中——桶的取水逻辑住在 Item.use 里、自带 SOURCE_ONLY 射线,
     * 靠的是"方块没吃掉点击就落到物品自用"那步兜底。守住它:没有兜底时
     * 对水右键永远空手,工具却报成功。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_interact")
    public static void interact_bucket_scoops_aimed_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 沉进地板的一格水:四邻就是地板块,天然围住;地表水盆的沿会挡住
        // 下探的视线(她的眼睛只比水面高一格半,射线在沿上就切进石头了)
        BlockPos water = helper.absolutePos(new BlockPos(5, 1, 5));
        level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());

        NumenPlayer companion = spawnAt(helper, "gametest_scooper", new BlockPos(3, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.BUCKET));
        ToolRun scoop = call(companion, "interact_at", args("button", "right",
                "x", water.getX(), "y", water.getY(), "z", water.getZ(), "item_id", "minecraft:bucket"));

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.WATER_BUCKET) == 1,
                    "the bucket did not scoop the aimed water — tool reply: " + scoop.outcome());
            helper.assertTrue(!level.getBlockState(water).getFluidState().isSource(),
                    "the aimed water source is still there");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 船对水右键:BoatItem 的行为同样住在 Item.use 里(Fluid.ANY 自射线),
     * 生成位与身体重叠还会被原版 noCollision 静默拒绝——所以她站在岸上、
     * 瞄几格外的池心。守的是同一步兜底 + "放出去的船真的存在"。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_interact")
    public static void interact_boat_places_on_aimed_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 3x3 水池,外圈一格石堤
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                boolean rim = x == 6 || x == 10 || z == 6 || z == 10;
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 2, z)),
                        rim ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_sailor", new BlockPos(5, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.OAK_BOAT));
        // 瞄远列而不是池心:视线在下降途中提前碰到水面,命中点比瞄点近一截;
        // 瞄池心时船的碰撞箱(宽 1.375)会搭在石堤上被 noCollision 拒绝——
        // 真玩家放船也是往远处的水面看,不盯着脚边的岸沿。
        BlockPos aim = helper.absolutePos(new BlockPos(9, 2, 8));
        ToolRun place = call(companion, "interact_at", args("button", "right",
                "x", aim.getX(), "y", aim.getY(), "z", aim.getZ(), "item_id", "minecraft:oak_boat"));

        helper.succeedWhen(() -> {
            var boats = level.getEntitiesOfClass(net.minecraft.world.entity.vehicle.Boat.class,
                    new net.minecraft.world.phys.AABB(
                            helper.absolutePos(new BlockPos(6, 1, 6)).getCenter(),
                            helper.absolutePos(new BlockPos(10, 4, 10)).getCenter()));
            helper.assertTrue(!boats.isEmpty(),
                    "no boat appeared on the aimed water — tool reply: " + place.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 拿剪刀右键一头羊:她走过去剪了毛,羊身上的毛没了。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_interact")
    public static void interact_entity_shears_a_sheep(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Sheep sheep = net.minecraft.world.entity.EntityType.SHEEP.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(10, 2, 4));
        sheep.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        sheep.setNoAi(true);
        helper.getLevel().addFreshEntity(sheep);
        NumenPlayer companion = spawnAt(helper, "gametest_shearer", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.SHEARS));
        ToolRun shear = call(companion, "interact_entity",
                args("button", "right", "entity_id", sheep.getId(), "item_id", "minecraft:shears"));

        helper.succeedWhen(() -> {
            helper.assertTrue(shear.done(), "interact_entity has not finished");
            helper.assertTrue(shear.succeeded() && sheep.isSheared(), "the sheep was not sheared: " + shear.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 左键一头猪:她走过去打了一下,猪掉了血。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_interact")
    public static void interact_entity_left_click_hits_a_pig(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(10, 2, 11));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        NumenPlayer companion = spawnAt(helper, "gametest_poker_entity", new BlockPos(3, 2, 11), false);
        ToolRun hit = call(companion, "interact_entity", args("button", "left", "entity_id", pig.getId()));

        helper.succeedWhen(() -> {
            helper.assertTrue(hit.done(), "interact_entity has not finished");
            helper.assertTrue(hit.succeeded() && pig.getHealth() < pig.getMaxHealth()
                            && pig.getLastHurtByMob() == companion,
                    "the pig was not hit by her: " + hit.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 目标在工作距离外:interact_at 不自己走过去,当场失败并叫她先 goto,那一格原样。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void interact_at_out_of_reach_says_goto_first(GameTestHelper helper) {
        BlockPos stone = helper.absolutePos(new BlockPos(13, 2, 13));
        helper.getLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_shortarmed", new BlockPos(2, 2, 2), false);
        ToolRun click = call(companion, "interact_at",
                args("button", "left", "x", stone.getX(), "y", stone.getY(), "z", stone.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(click.done(), "interact_at has not finished");
            helper.assertTrue(!click.succeeded() && click.outcome().contains("out of working reach")
                            && click.outcome().contains("goto"),
                    "the failure does not send her to goto first: " + click.outcome());
            helper.assertTrue(helper.getLevel().getBlockState(stone).is(Blocks.STONE), "the stone was touched");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 有名字的猪要问主人才能打;主人不在,问不到就不打,猪一滴血没掉。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_interact")
    public static void interact_entity_on_a_named_animal_needs_the_owner(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        pig.setCustomName(net.minecraft.network.chat.Component.literal("Wilbur"));
        helper.getLevel().addFreshEntity(pig);
        NumenPlayer companion = spawnAt(helper, "gametest_restrained", new BlockPos(3, 2, 8), false);
        ToolRun hit = call(companion, "interact_entity", args("button", "left", "entity_id", pig.getId()));

        helper.succeedWhen(() -> {
            helper.assertTrue(hit.done(), "interact_entity has not finished");
            helper.assertTrue(!hit.succeeded() && hit.outcome().contains("owner"),
                    "the refusal does not come from asking the owner: " + hit.outcome());
            helper.assertTrue(pig.getHealth() == pig.getMaxHealth(), "the named pig was hit");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    // ---- interact_entity:挤奶、喂食;interact_at:门、拉杆、放方块 ----

    /** 拿空桶右键一头牛:她走过去挤了奶,空桶换成了一桶牛奶。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_interact")
    public static void interact_entity_milks_a_cow(GameTestHelper helper) {
        var cow = net.minecraft.world.entity.EntityType.COW.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(10, 2, 7));
        cow.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        cow.setNoAi(true);
        helper.getLevel().addFreshEntity(cow);
        NumenPlayer companion = spawnAt(helper, "gametest_milkmaid", new BlockPos(3, 2, 7), false);
        companion.getInventory().add(new ItemStack(Items.BUCKET));
        ToolRun milk = call(companion, "interact_entity",
                args("button", "right", "entity_id", cow.getId(), "item_id", "minecraft:bucket"));

        helper.succeedWhen(() -> {
            helper.assertTrue(milk.done(), "interact_entity has not finished");
            helper.assertTrue(milk.succeeded() && companion.getInventory().countItem(Items.MILK_BUCKET) == 1
                            && companion.getInventory().countItem(Items.BUCKET) == 0,
                    "the bucket was not filled with milk: " + milk.outcome());
            cow.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 拿小麦右键一头成年牛:喂下去一根,牛进了求偶状态。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_interact")
    public static void interact_entity_feeds_a_cow_wheat(GameTestHelper helper) {
        var cow = net.minecraft.world.entity.EntityType.COW.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(10, 2, 11));
        cow.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        cow.setNoAi(true);
        helper.getLevel().addFreshEntity(cow);
        NumenPlayer companion = spawnAt(helper, "gametest_cowherd", new BlockPos(3, 2, 11), false);
        companion.getInventory().add(new ItemStack(Items.WHEAT, 2));
        ToolRun feed = call(companion, "interact_entity",
                args("button", "right", "entity_id", cow.getId(), "item_id", "minecraft:wheat"));

        helper.succeedWhen(() -> {
            helper.assertTrue(feed.done(), "interact_entity has not finished");
            helper.assertTrue(feed.succeeded() && cow.isInLove() && companion.getInventory().countItem(Items.WHEAT) == 1,
                    "the cow was not fed: " + feed.outcome());
            cow.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 右键一扇关着的木门:门开了;再右键一次:门又关上。两次回执都说出了门的变化。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_interact")
    public static void interact_at_opens_then_closes_a_door(GameTestHelper helper) {
        BlockPos lower = helper.absolutePos(new BlockPos(6, 2, 4));
        var door = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.WEST);
        helper.getLevel().setBlock(lower, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 3);
        helper.getLevel().setBlock(lower.above(), door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
        NumenPlayer companion = spawnAt(helper, "gametest_porter", new BlockPos(4, 2, 4), false);
        java.util.function.BooleanSupplier open = () -> helper.getLevel().getBlockState(lower)
                .getValue(net.minecraft.world.level.block.DoorBlock.OPEN);
        java.util.concurrent.atomic.AtomicReference<TaskRecord> click = new java.util.concurrent.atomic.AtomicReference<>();

        helper.startSequence()
                .thenExecute(() -> click.set(click(helper, companion, "right", new BlockPos(6, 2, 4))))
                .thenWaitUntil(() -> helper.assertTrue(click.get().getResult() != null && open.getAsBoolean(),
                        "the door did not open: " + click.get().getResult()))
                .thenExecute(() -> click.set(click(helper, companion, "right", new BlockPos(6, 2, 4))))
                .thenWaitUntil(() -> helper.assertTrue(click.get().getResult() != null && !open.getAsBoolean(),
                        "the door did not close again: " + click.get().getResult()))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 右键地上的拉杆:拉杆扳下去了(通电),回执说那一格变了。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void interact_at_flips_a_lever(GameTestHelper helper) {
        BlockPos lever = helper.absolutePos(new BlockPos(6, 2, 8));
        helper.getLevel().setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        NumenPlayer companion = spawnAt(helper, "gametest_switcher", new BlockPos(4, 2, 8), false);
        TaskRecord flip = click(helper, companion, "right", new BlockPos(6, 2, 8));

        helper.succeedWhen(() -> {
            helper.assertTrue(flip.getResult() != null, "interact_at has not finished");
            helper.assertTrue(flip.getResult().success() && helper.getLevel().getBlockState(lever)
                            .getValue(net.minecraft.world.level.block.LeverBlock.POWERED),
                    "the lever was not flipped: " + flip.getResult().message());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 拿着圆石右键脚边的地面:圆石放在了那块地面上面一格,手里少了一块。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void interact_at_places_a_block_on_the_floor(GameTestHelper helper) {
        BlockPos floor = helper.absolutePos(new BlockPos(6, 1, 12));
        NumenPlayer companion = spawnAt(helper, "gametest_paver", new BlockPos(4, 2, 12), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 4));
        ToolRun place = call(companion, "interact_at", args("button", "right",
                "x", floor.getX(), "y", floor.getY(), "z", floor.getZ(), "item_id", "minecraft:cobblestone"));

        helper.succeedWhen(() -> {
            helper.assertTrue(place.done(), "interact_at has not finished");
            helper.assertTrue(place.succeeded() && helper.getLevel().getBlockState(floor.above()).is(Blocks.COBBLESTONE)
                            && companion.getInventory().countItem(Items.COBBLESTONE) == 3,
                    "the cobblestone was not placed on the floor: " + place.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
