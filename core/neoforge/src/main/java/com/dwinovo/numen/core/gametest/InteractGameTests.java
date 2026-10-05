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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 交互:{@code numen.use.block} 对着水面舀水、放船;{@code numen.use.entity} 走到活物跟前右键、左键。 */
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
        ToolRun scoop = lua(companion, "numen.use.block(" + xyz(water) + ", {item = \"minecraft:bucket\"})");

        succeedWhen(helper, () -> {
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
        ToolRun place = lua(companion, "numen.use.block(" + xyz(aim) + ", {item = \"minecraft:oak_boat\"})");

        succeedWhen(helper, () -> {
            var boats = level.getEntitiesOfClass(net.minecraft.world.entity.vehicle.Boat.class,
                    new net.minecraft.world.phys.AABB(
                            helper.absolutePos(new BlockPos(6, 1, 6)).getCenter(),
                            helper.absolutePos(new BlockPos(10, 4, 10)).getCenter()));
            helper.assertTrue(!boats.isEmpty(),
                    "no boat appeared on the aimed water — tool reply: " + place.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 拿剪刀右键手边的一头羊:剪了毛,羊身上的毛没了。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_interact")
    public static void interact_entity_shears_a_sheep(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Sheep sheep = net.minecraft.world.entity.EntityType.SHEEP.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(10, 2, 4));
        sheep.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        sheep.setNoAi(true);
        helper.getLevel().addFreshEntity(sheep);
        NumenPlayer companion = spawnAt(helper, "gametest_shearer", new BlockPos(8, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.SHEARS));
        ToolRun shear = lua(companion, "numen.use.entity(" + sheep.getId() + ", {item = \"minecraft:shears\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(shear.done(), "use entity has not finished");
            helper.assertTrue(shear.succeeded() && sheep.isSheared(), "the sheep was not sheared: " + shear.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 左键手边的一头猪:打了一下,猪掉了血。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_interact")
    public static void interact_entity_left_click_hits_a_pig(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(10, 2, 11));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        NumenPlayer companion = spawnAt(helper, "gametest_poker_entity", new BlockPos(8, 2, 11), false);
        ToolRun hit = lua(companion, "numen.use.hit(" + pig.getId() + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(hit.done(), "use entity has not finished");
            helper.assertTrue(hit.succeeded() && pig.getHealth() < pig.getMaxHealth()
                            && pig.getLastHurtByMob() == companion,
                    "the pig was not hit by her: " + hit.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 不对准任何一格:朝她面对的方向用手里的东西——雪球扔了出去,手里少一个。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void use_ahead_throws_the_held_item(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_pitcher", new BlockPos(4, 2, 8), false);
        companion.setXRot(-30f);
        companion.getInventory().add(new ItemStack(Items.SNOWBALL, 4));
        ToolRun toss = lua(companion, "numen.use.item({item = \"minecraft:snowball\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(toss.done(), "use item has not finished");
            helper.assertTrue(toss.succeeded() && companion.getInventory().countItem(Items.SNOWBALL) == 3,
                    "the snowball was not thrown: " + toss.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 目标在工作距离外:numen.use.hit 不自己走过去,当场失败,下一步是能照抄的 numen.move.to(…, {arrive = "use"}),那一格原样。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void interact_at_out_of_reach_says_goto_first(GameTestHelper helper) {
        BlockPos stone = helper.absolutePos(new BlockPos(13, 2, 13));
        helper.getLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_shortarmed", new BlockPos(2, 2, 2), false);
        ToolRun click = lua(companion, "numen.use.hit(" + xyz(stone) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(click.done(), "use hit has not finished");
            helper.assertTrue(!click.succeeded() && click.outcome().contains("out of working reach")
                            && click.outcome().contains("`numen.move.to(" + xyz(stone) + ", {arrive = \"use\"})`"),
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
        NumenPlayer companion = spawnAt(helper, "gametest_restrained", new BlockPos(6, 2, 8), false);
        ToolRun hit = lua(companion, "numen.use.hit(" + pig.getId() + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(hit.done(), "use entity has not finished");
            helper.assertTrue(!hit.succeeded() && hit.outcome().contains("owner"),
                    "the refusal does not come from asking the owner: " + hit.outcome());
            helper.assertTrue(pig.getHealth() == pig.getMaxHealth(), "the named pig was hit");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    // ---- numen.use.entity:挤奶、喂食;numen.use.block:门、拉杆、放方块 ----

    /** 拿空桶右键手边的一头牛:挤了奶,空桶换成了一桶牛奶。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_interact")
    public static void interact_entity_milks_a_cow(GameTestHelper helper) {
        var cow = net.minecraft.world.entity.EntityType.COW.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(10, 2, 7));
        cow.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        cow.setNoAi(true);
        helper.getLevel().addFreshEntity(cow);
        NumenPlayer companion = spawnAt(helper, "gametest_milkmaid", new BlockPos(8, 2, 7), false);
        companion.getInventory().add(new ItemStack(Items.BUCKET));
        ToolRun milk = lua(companion, "numen.use.entity(" + cow.getId() + ", {item = \"minecraft:bucket\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(milk.done(), "use entity has not finished");
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
        NumenPlayer companion = spawnAt(helper, "gametest_cowherd", new BlockPos(8, 2, 11), false);
        companion.getInventory().add(new ItemStack(Items.WHEAT, 2));
        ToolRun feed = lua(companion, "numen.use.entity(" + cow.getId() + ", {item = \"minecraft:wheat\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(feed.done(), "use entity has not finished");
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
        java.util.concurrent.atomic.AtomicReference<ToolRun> click = new java.util.concurrent.atomic.AtomicReference<>();

        steps(helper)
                .thenExecute(() -> click.set(click(helper, companion, "right", new BlockPos(6, 2, 4))))
                .thenWaitUntil(() -> helper.assertTrue(click.get().done() && open.getAsBoolean(),
                        "the door did not open: " + click.get().outcome()))
                .thenExecute(() -> click.set(click(helper, companion, "right", new BlockPos(6, 2, 4))))
                .thenWaitUntil(() -> helper.assertTrue(click.get().done() && !open.getAsBoolean(),
                        "the door did not close again: " + click.get().outcome()))
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
        ToolRun flip = click(helper, companion, "right", new BlockPos(6, 2, 8));

        succeedWhen(helper, () -> {
            helper.assertTrue(flip.done(), "use block has not finished");
            helper.assertTrue(flip.succeeded() && helper.getLevel().getBlockState(lever)
                            .getValue(net.minecraft.world.level.block.LeverBlock.POWERED),
                    "the lever was not flipped: " + flip.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    // ---- numen.use.entity 对宠物:她自己的不问,别人的问;--sneak;对她说的话 ----

    /** 一只不动的狼,放在 {@code rel} 那一格。 */
    static net.minecraft.world.entity.animal.Wolf wolfAt(GameTestHelper helper, BlockPos rel) {
        var wolf = net.minecraft.world.entity.EntityType.WOLF.create(helper.getLevel());
        BlockPos at = helper.absolutePos(rel);
        wolf.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        wolf.setNoAi(true);
        helper.getLevel().addFreshEntity(wolf);
        return wolf;
    }

    /** 右键她自己驯服的狼:出厂的 {@code use_entity(self_owned)} 放行,不问主人,狼坐下了。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_interact")
    public static void use_entity_on_her_own_wolf_does_not_ask(GameTestHelper helper) {
        var wolf = wolfAt(helper, new BlockPos(10, 2, 14));
        NumenPlayer companion = spawnAt(helper, "gametest_wolfkeeper", new BlockPos(8, 2, 14), false);
        wolf.tame(companion);
        ToolRun sit = lua(companion, "numen.use.entity(" + wolf.getId() + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(sit.done(), "use entity has not finished");
            helper.assertTrue(sit.succeeded() && wolf.isOrderedToSit(),
                    "her own wolf was not told to sit: " + sit.outcome());
            wolf.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 右键别人驯服的狼:没有一行放行,问主人;主人不在,问不到就不点,狼没坐下。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_interact")
    public static void use_entity_on_someone_elses_wolf_asks_the_owner(GameTestHelper helper) {
        var wolf = wolfAt(helper, new BlockPos(10, 2, 2));
        wolf.setTame(true, true);
        wolf.setOwnerUUID(java.util.UUID.randomUUID());
        NumenPlayer companion = spawnAt(helper, "gametest_wolfpetter", new BlockPos(8, 2, 2), false);
        ToolRun pet = lua(companion, "numen.use.entity(" + wolf.getId() + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(pet.done(), "use entity has not finished");
            helper.assertTrue(!pet.succeeded() && pet.outcome().contains("owner"),
                    "the refusal does not come from asking the owner: " + pet.outcome());
            helper.assertTrue(!wolf.isOrderedToSit(), "someone else's wolf was told to sit");
            wolf.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 拿着圆石右键一只箱子:站着点,箱子开了;按住潜行点,箱子不开,圆石贴着箱子放下了——手里有东西时潜行右键越过方块自己的反应,
     * 只有潜行才有。点完潜行松开,回执说是潜行点的。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_interact")
    public static void use_block_sneaking_puts_a_block_on_a_chest_instead_of_opening_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = helper.absolutePos(new BlockPos(6, 2, 14));
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_sneaker", new BlockPos(4, 2, 14), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 4));
        String click = "numen.use.block(" + xyz(chest) + ", {item = \"minecraft:cobblestone\"";
        java.util.concurrent.atomic.AtomicReference<ToolRun> run = new java.util.concurrent.atomic.AtomicReference<>();

        steps(helper)
                .thenExecute(() -> run.set(lua(companion, click + "})")))
                .thenWaitUntil(() -> helper.assertTrue(run.get().done()
                                && companion.containerMenu instanceof net.minecraft.world.inventory.ChestMenu,
                        "standing, the right click did not open the chest: " + run.get().outcome()))
                .thenExecute(() -> run.set(lua(companion, "numen.gui.close()")))
                .thenWaitUntil(() -> helper.assertTrue(run.get().done()
                                && companion.containerMenu == companion.inventoryMenu,
                        "the chest did not close: " + run.get().outcome()))
                .thenExecute(() -> run.set(lua(companion, click + ", sneak = true})")))
                .thenWaitUntil(() -> {
                    helper.assertTrue(run.get().done(), "the sneaking click has not finished");
                    helper.assertTrue(run.get().succeeded() && run.get().outcome().contains("while sneaking"),
                            "the receipt does not say she clicked sneaking: " + run.get().outcome());
                    helper.assertTrue(companion.containerMenu == companion.inventoryMenu,
                            "sneaking, the right click still opened the chest");
                    boolean placed = false;
                    for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
                        placed |= level.getBlockState(chest.relative(side)).is(Blocks.COBBLESTONE);
                    }
                    helper.assertTrue(placed && companion.getInventory().countItem(Items.COBBLESTONE) == 3,
                            "no cobblestone went onto the chest: " + run.get().outcome());
                    helper.assertTrue(!companion.controls().held(com.dwinovo.numen.pathing.body.Controls.Key.SNEAK),
                            "she still holds sneak after the click");
                })
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), companion))
                .thenSucceed();
    }

    /**
     * 正午右键一张床:原版在动作栏里告诉她只能在夜里睡,这句话作为一条 {@code server_message} 事件交给了模型
     * (主人不在,进了出箱)。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_interact")
    public static void a_bed_at_noon_tells_her_why_as_an_event(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos foot = helper.absolutePos(new BlockPos(6, 2, 2));
        var bed = Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.EAST);
        level.setBlock(foot, bed.setValue(net.minecraft.world.level.block.BedBlock.PART,
                net.minecraft.world.level.block.state.properties.BedPart.FOOT), 3);
        level.setBlock(foot.east(), bed.setValue(net.minecraft.world.level.block.BedBlock.PART,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
        NumenPlayer companion = spawnAt(helper, "gametest_napper", new BlockPos(4, 2, 2), false);
        ToolRun lie = lua(companion, "numen.use.block(" + xyz(foot) + ")");
        String onlyAtNight = net.minecraft.network.chat.Component.translatable("block.minecraft.bed.no_sleep")
                .getString();

        succeedWhen(helper, () -> {
            helper.assertTrue(lie.done(), "use block has not finished");
            java.util.List<String> told = com.dwinovo.numen.entity.EventOutbox.get(level.getServer())
                    .peek(companion.getUUID()).entries().stream()
                    .filter(e -> com.dwinovo.numen.agent.inbox.EventTypes.SERVER_MESSAGE.equals(e.type()))
                    .map(com.dwinovo.numen.agent.inbox.EventQueue.Entry::text)
                    .toList();
            helper.assertTrue(told.stream().anyMatch(t -> t.contains("where=\"action_bar\"")
                            && t.contains(onlyAtNight)),
                    "what the bed told her is not an event: " + told + " — tool reply: " + lie.outcome());
            helper.assertTrue(!companion.isSleeping(), "she fell asleep at noon");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 左键是一次纯按键:创造模式的她手里拿着一根木棍,包里有一把铁锹,{@code numen.use.hit} 对着一块泥土——她用木棍点掉它
     * (创造里点一下就碎),不去换锹;锹原样躺在包里那一格,手里还是木棍,回执里没有换工具这一句。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_interact")
    public static void use_block_left_hits_with_whatever_is_in_hand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos dirt = helper.absolutePos(new BlockPos(5, 2, 8));
        level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_bare_press", new BlockPos(3, 2, 8), true);
        var inventory = companion.getInventory();
        inventory.selected = 0;
        inventory.setItem(0, new ItemStack(Items.STICK));
        inventory.setItem(5, new ItemStack(Items.IRON_SHOVEL));
        ToolRun press = lua(companion, "numen.use.hit(" + xyz(dirt) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(press.done(), "use hit has not finished");
            helper.assertTrue(press.succeeded() && level.getBlockState(dirt).isAir(),
                    "the dirt was not broken: " + press.outcome());
            helper.assertTrue(inventory.selected == 0 && companion.getMainHandItem().is(Items.STICK)
                            && inventory.getItem(5).is(Items.IRON_SHOVEL),
                    "she swapped tools for a bare key press: holding " + companion.getMainHandItem());
            helper.assertTrue(press.outcome().contains("left-clicked " + words(dirt).replace(' ', ',')),
                    "the reply does not say what was clicked: " + press.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 准星落在谁就按谁:创造模式的她和石头之间隔着一块泥土,{@code numen.use.hit} 对着石头按下去,落在泥土上——点掉的是泥土,
     * 石头原样;回执照实说准星落在了泥土上,不是瞄的那一格。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_interact")
    public static void use_block_left_presses_what_the_crosshair_lands_on(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stone = helper.absolutePos(new BlockPos(6, 2, 12));
        BlockPos dirt = helper.absolutePos(new BlockPos(5, 2, 12));
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_crosshair", new BlockPos(3, 2, 12), true);
        ToolRun press = lua(companion, "numen.use.hit(" + xyz(stone) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(press.done(), "use hit has not finished");
            helper.assertTrue(press.succeeded() && level.getBlockState(dirt).isAir()
                            && level.getBlockState(stone).is(Blocks.STONE),
                    "the press did not land on the dirt in front: " + press.outcome());
            helper.assertTrue(press.outcome().contains("dirt at " + dirt.getX() + "," + dirt.getY() + ","
                            + dirt.getZ() + " — the crosshair landed there, not on " + stone.getX() + ","
                            + stone.getY() + "," + stone.getZ()),
                    "the reply does not say the crosshair landed elsewhere: " + press.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 拿着圆石右键脚边的地面:圆石放在了那块地面上面一格,手里少了一块。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void interact_at_places_a_block_on_the_floor(GameTestHelper helper) {
        BlockPos floor = helper.absolutePos(new BlockPos(6, 1, 12));
        NumenPlayer companion = spawnAt(helper, "gametest_paver", new BlockPos(4, 2, 12), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 4));
        ToolRun place = lua(companion, "numen.use.block(" + xyz(floor) + ", {item = \"minecraft:cobblestone\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(place.done(), "use block has not finished");
            helper.assertTrue(place.succeeded() && helper.getLevel().getBlockState(floor.above()).is(Blocks.COBBLESTONE)
                            && companion.getInventory().countItem(Items.COBBLESTONE) == 3,
                    "the cobblestone was not placed on the floor: " + place.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
