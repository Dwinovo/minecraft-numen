package com.dwinovo.numen.plugins.minecraft.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import static com.dwinovo.numen.plugins.minecraft.gametest.GameTestKit.*;

/** 交互:{@code minecraft.use.block} 对着水面舀水、放船;{@code minecraft.use.entity} 走到活物跟前右键、左键。 */
public class InteractGameTests {

    /** 交互批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_interact")
    public static void prepareInteractBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 桶对水右键:右键的完整管线钉桩。点的是那一格水,水不接这一下——桶的取水逻辑住在 Item.use 里、自带 SOURCE_ONLY 射线,
     * 靠的是"方块没吃掉点击就落到物品自用"那步兜底。守住它:没有兜底时对水右键永远空手,工具却报成功。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void interact_bucket_scoops_aimed_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 沉进地板的一格水:四邻就是地板块,天然围住;地表水盆的沿会挡住桶自己沿视线打的那一条
        // (她的眼睛只比水面高一格半,射线在沿上就切进石头了)
        BlockPos water = helper.absolutePos(new BlockPos(5, 1, 5));
        level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());

        NumenPlayer companion = spawnAt(helper, "gametest_scooper", new BlockPos(3, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.BUCKET));
        ToolRun scoop = lua(companion, "minecraft.use.block(" + xyz(water) + ", {item = \"minecraft:bucket\"})");

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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
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
        // 点远列而不是池心:船自己沿视线打的那一条在下降途中提前碰到水面,落点比点的那一格近一截;
        // 点池心时船的碰撞箱(宽 1.375)会搭在石堤上被 noCollision 拒绝。
        BlockPos aim = helper.absolutePos(new BlockPos(9, 2, 8));
        ToolRun place = lua(companion, "minecraft.use.block(" + xyz(aim) + ", {item = \"minecraft:oak_boat\"})");

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

    /** 左键手边的一头猪:打了一下,猪掉了血。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void interact_entity_left_click_hits_a_pig(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(10, 2, 11));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        NumenPlayer companion = spawnAt(helper, "gametest_poker_entity", new BlockPos(8, 2, 11), false);
        ToolRun hit = lua(companion, "minecraft.use.hit(" + ref(pig) + ")");

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
        ToolRun toss = lua(companion, "minecraft.use.item({item = \"minecraft:snowball\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(toss.done(), "use item has not finished");
            helper.assertTrue(toss.succeeded() && companion.getInventory().countItem(Items.SNOWBALL) == 3,
                    "the snowball was not thrown: " + toss.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 右键的回执只说游戏接没接(control 0022):拿打火石点一堆没点着的营火,营火接了这一下,回执写 accepted、营火点着了;空手右键一块石头,
     * 谁都不理,回执写 not accepted,这一下照常算做完。回执里没有"变了什么"。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void a_right_click_says_whether_the_game_accepted_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos campfire = helper.absolutePos(new BlockPos(6, 2, 8));
        level.setBlockAndUpdate(campfire, Blocks.CAMPFIRE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false));
        BlockPos stone = helper.absolutePos(new BlockPos(6, 2, 3));
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        NumenPlayer lighter = spawnAt(helper, "gametest_lighter", new BlockPos(4, 2, 8), false);
        lighter.getInventory().add(new ItemStack(Items.FLINT_AND_STEEL));
        NumenPlayer idler = spawnAt(helper, "gametest_idler", new BlockPos(4, 2, 3), false);
        ToolRun light = lua(lighter, "minecraft.use.block(" + xyz(campfire) + ", {item = \"minecraft:flint_and_steel\"})");
        ToolRun poke = lua(idler, "minecraft.use.block(" + xyz(stone) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(light.done() && poke.done(), "the clicks have not finished");
            helper.assertTrue(light.succeeded()
                            && level.getBlockState(campfire).getValue(net.minecraft.world.level.block.CampfireBlock.LIT),
                    "the campfire was not lit: " + light.reply());
            helper.assertTrue(light.outcome().contains(" — accepted")
                            && light.result(com.dwinovo.numen.plugins.minecraft.tools.Clicks.Clicked.class).accepted(),
                    "the receipt does not say the campfire took the click and nothing more: " + light.outcome());
            helper.assertTrue(poke.succeeded() && poke.outcome().contains("not accepted: nothing there responds")
                            && !poke.result(com.dwinovo.numen.plugins.minecraft.tools.Clicks.Clicked.class).accepted(),
                    "an empty-handed click on stone is not reported as ignored: " + poke.outcome());
            CompanionFactory.despawn(level.getServer(), lighter);
            CompanionFactory.despawn(level.getServer(), idler);
        });
    }

    /** 鼠标左键打实体先过权限层,谁驱动她都绕不开:只许看的模式下直接 mouse().attack(cow) 被拒、牛一滴血没掉;放行了才打下去。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_interact")
    public static void the_mouse_asks_the_permission_layer_before_hitting_an_entity(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Cow cow = net.minecraft.world.entity.EntityType.COW.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        cow.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        cow.setNoAi(true);
        helper.getLevel().addFreshEntity(cow);
        NumenPlayer companion = spawnAt(helper, "gametest_swinger", new BlockPos(6, 2, 8), false);
        com.dwinovo.numen.api.permission.PermissionStore.of(companion.getServer(), companion.getOwnerUuid())
                .preset(companion.getUUID(), com.dwinovo.numen.permission.Preset.OBSERVE);

        steps(helper)
                .thenExecute(() -> companion.look().at(cow.getEyePosition()))
                .thenExecute(() -> {
                    var blow = companion.mouse().attack(cow);
                    helper.assertTrue(blow instanceof com.dwinovo.numen.api.entity.Mouse.Blow.Refused
                                    && cow.getHealth() == cow.getMaxHealth(),
                            "the mouse hit the cow in observe mode: " + blow);
                    com.dwinovo.numen.api.permission.PermissionStore.of(companion.getServer(), companion.getOwnerUuid())
                .preset(companion.getUUID(), com.dwinovo.numen.permission.Preset.BYPASS);
                })
                .thenExecute(() -> {
                    var blow = companion.mouse().attack(cow);
                    helper.assertTrue(blow instanceof com.dwinovo.numen.api.entity.Mouse.Blow.Landed
                                    && cow.getHealth() < cow.getMaxHealth(),
                            "the allowed hit did not land: " + blow);
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /** 目标在工作距离外:minecraft.use.hit 先自己走到看得见、够得着它的地方,再点一下;石头点一下碎不了,原样。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void interact_at_out_of_reach_walks_up_first(GameTestHelper helper) {
        BlockPos stone = helper.absolutePos(new BlockPos(13, 2, 13));
        helper.getLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_shortarmed", new BlockPos(2, 2, 2), false);
        ToolRun click = lua(companion, "minecraft.use.hit(" + xyz(stone) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(click.done(), "use hit has not finished");
            helper.assertTrue(click.succeeded() && click.outcome().startsWith("left-clicked"),
                    "she did not walk up and hit it: " + click.outcome());
            helper.assertTrue(companion.canInteractWithBlock(stone, 0.0), "she hit it from out of reach");
            helper.assertTrue(helper.getLevel().getBlockState(stone).is(Blocks.STONE), "one hit broke the stone");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    // ---- minecraft.use.entity:挤奶;minecraft.use.block:门、放方块 ----

    /** 拿空桶右键手边的一头牛:挤了奶,空桶换成了一桶牛奶;回执只说牛接了这一下,背包里变了什么她自己看。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void interact_entity_milks_a_cow(GameTestHelper helper) {
        var cow = net.minecraft.world.entity.EntityType.COW.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(10, 2, 7));
        cow.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        cow.setNoAi(true);
        helper.getLevel().addFreshEntity(cow);
        NumenPlayer companion = spawnAt(helper, "gametest_milkmaid", new BlockPos(8, 2, 7), false);
        companion.getInventory().add(new ItemStack(Items.BUCKET));
        ToolRun milk = lua(companion, "minecraft.use.entity(" + ref(cow) + ", {item = \"minecraft:bucket\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(milk.done(), "use entity has not finished");
            helper.assertTrue(milk.succeeded() && companion.getInventory().countItem(Items.MILK_BUCKET) == 1
                            && companion.getInventory().countItem(Items.BUCKET) == 0,
                    "the bucket was not filled with milk: " + milk.outcome());
            helper.assertTrue(milk.outcome().contains(" — accepted") && !milk.outcome().contains("milk_bucket"),
                    "the reply does not say just that the cow took the click: " + milk.outcome());
            cow.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 右键一扇关着的木门:门开了;再右键一次:门又关上。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
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

    // ---- minecraft.use.entity 对宠物:她自己的不问,别人的问;--sneak;对她说的话 ----

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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void use_entity_on_her_own_wolf_does_not_ask(GameTestHelper helper) {
        var wolf = wolfAt(helper, new BlockPos(10, 2, 14));
        NumenPlayer companion = spawnAt(helper, "gametest_wolfkeeper", new BlockPos(8, 2, 14), false);
        wolf.tame(companion);
        ToolRun sit = lua(companion, "minecraft.use.entity(" + ref(wolf) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(sit.done(), "use entity has not finished");
            helper.assertTrue(sit.succeeded() && wolf.isOrderedToSit(),
                    "her own wolf was not told to sit: " + sit.outcome());
            wolf.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 拿着圆石右键一只箱子:站着点,箱子接了这一下(回执写 accepted,圆石一块没少),点开的界面当场合上了;按住潜行点,箱子不理,
     * 圆石贴着箱子放下了——手里有东西时潜行右键越过方块自己的反应,只有潜行才有。点完潜行松开,回执说是潜行点的。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void use_block_sneaking_puts_a_block_on_a_chest_instead_of_opening_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = helper.absolutePos(new BlockPos(6, 2, 14));
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_sneaker", new BlockPos(4, 2, 14), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 4));
        String click = "minecraft.use.block(" + xyz(chest) + ", {item = \"minecraft:cobblestone\"";
        java.util.concurrent.atomic.AtomicReference<ToolRun> run = new java.util.concurrent.atomic.AtomicReference<>();

        steps(helper)
                .thenExecute(() -> run.set(lua(companion, click + "})")))
                .thenWaitUntil(() -> {
                    helper.assertTrue(run.get().done(), "the standing click has not finished");
                    helper.assertTrue(run.get().succeeded() && run.get().outcome().contains(" — accepted")
                                    && companion.getInventory().countItem(Items.COBBLESTONE) == 4,
                            "standing, the chest did not take the right click: " + run.get().outcome());
                    helper.assertTrue(companion.containerMenu == companion.inventoryMenu,
                            "the chest the click opened was left open");
                })
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
                    helper.assertTrue(!companion.controls().held(com.dwinovo.numen.api.entity.Controls.Key.SNEAK),
                            "she still holds sneak after the click");
                })
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), companion))
                .thenSucceed();
    }

    /**
     * 正午右键一张床:原版在动作栏里告诉她只能在夜里睡,这句话作为一条 {@code server_message} 事件交给了模型
     * (进了她的收件箱)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
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
        ToolRun lie = lua(companion, "minecraft.use.block(" + xyz(foot) + ")");
        String onlyAtNight = net.minecraft.network.chat.Component.translatable("block.minecraft.bed.no_sleep")
                .getString();

        succeedWhen(helper, () -> {
            helper.assertTrue(lie.done(), "use block has not finished");
            java.util.List<String> told = GameTestKit.events(companion).stream()
                    .filter(e -> com.dwinovo.numen.api.agent.inbox.EventTypes.SERVER_MESSAGE.equals(e.type()))
                    .map(com.dwinovo.numen.api.agent.inbox.EventQueue.Entry::text)
                    .toList();
            helper.assertTrue(told.stream().anyMatch(t -> t.contains("where=\"action_bar\"")
                            && t.contains(onlyAtNight)),
                    "what the bed told her is not an event: " + told + " — tool reply: " + lie.outcome());
            helper.assertTrue(!companion.isSleeping(), "she fell asleep at noon");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 左键是一次纯按键:创造模式的她手里拿着一根木棍,包里有一把铁锹,{@code minecraft.use.hit} 对着一块泥土——她用木棍点掉它
     * (创造里点一下就碎),不去换锹;锹原样躺在包里那一格,手里还是木棍,回执里没有换工具这一句。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void use_block_left_hits_with_whatever_is_in_hand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos dirt = helper.absolutePos(new BlockPos(5, 2, 8));
        level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_bare_press", new BlockPos(3, 2, 8), true);
        var inventory = companion.getInventory();
        inventory.selected = 0;
        inventory.setItem(0, new ItemStack(Items.STICK));
        inventory.setItem(5, new ItemStack(Items.IRON_SHOVEL));
        ToolRun press = lua(companion, "minecraft.use.hit(" + xyz(dirt) + ")");

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
     * 够得着就点得中,中间隔着什么不问:一根拉杆在一堵两格高的石墙后面,她在墙这边,手够得着拉杆、却看不见它。
     * {@code minecraft.use.block} 点名那根拉杆:拉杆扳过去了,墙上一格没少,她一步没动,回执说游戏接了这一下。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void use_block_reaches_through_a_wall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        java.util.List<BlockPos> wall = new java.util.ArrayList<>();
        for (int z = 11; z <= 13; z++) {
            for (int y = 2; y <= 3; y++) {
                BlockPos cell = helper.absolutePos(new BlockPos(6, y, z));
                level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
                wall.add(cell);
            }
        }
        BlockPos lever = helper.absolutePos(new BlockPos(7, 2, 12));
        level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        NumenPlayer companion = spawnAt(helper, "gametest_through_wall_click", new BlockPos(4, 2, 12), false);
        BlockPos stand = companion.blockPosition();
        ToolRun press = lua(companion, "minecraft.use.block(" + xyz(lever) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(press.done(), "use block has not finished");
            helper.assertTrue(press.succeeded() && level.getBlockState(lever).is(Blocks.LEVER)
                            && level.getBlockState(lever).getValue(net.minecraft.world.level.block.LeverBlock.POWERED),
                    "the lever behind the wall was not pulled: " + press.outcome());
            for (BlockPos cell : wall) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.STONE), "the wall in between was changed at " + cell);
            }
            helper.assertTrue(companion.blockPosition().equals(stand), "she moved to click a block within her reach");
            helper.assertTrue(press.result(com.dwinovo.numen.plugins.minecraft.tools.Clicks.Clicked.class).accepted(),
                    "the receipt does not say the lever took the click: " + press.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 拿着圆石右键脚边的地面:圆石放在了那块地面上面一格,手里少了一块。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_interact")
    public static void interact_at_places_a_block_on_the_floor(GameTestHelper helper) {
        BlockPos floor = helper.absolutePos(new BlockPos(6, 1, 12));
        NumenPlayer companion = spawnAt(helper, "gametest_paver", new BlockPos(4, 2, 12), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 4));
        ToolRun place = lua(companion, "minecraft.use.block(" + xyz(floor) + ", {item = \"minecraft:cobblestone\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(place.done(), "use block has not finished");
            helper.assertTrue(place.succeeded() && helper.getLevel().getBlockState(floor.above()).is(Blocks.COBBLESTONE)
                            && companion.getInventory().countItem(Items.COBBLESTONE) == 3,
                    "the cobblestone was not placed on the floor: " + place.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 写告示牌:放下一块牌子(服务端认她是能编辑的人),直接写正面;再写背面——这时牌子不再等她,她先像玩家那样右键它,才回包。
     * 两面的字都落在牌子上。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_interact")
    public static void use_sign_writes_both_sides_of_a_sign(GameTestHelper helper) {
        BlockPos floor = helper.absolutePos(new BlockPos(5, 1, 4));
        BlockPos sign = floor.above();
        NumenPlayer companion = spawnAt(helper, "gametest_writer", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.OAK_SIGN, 2));

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "minecraft.use.block(" + xyz(floor) + ", {item = \"minecraft:oak_sign\"})");
        steps = call(helper, steps, companion,
                "minecraft.use.sign(" + xyz(sign) + ", {\"Hello\", \"World\"})");
        steps = steps.thenWaitUntil(() -> {
            var text = (net.minecraft.world.level.block.entity.SignBlockEntity) helper.getLevel().getBlockEntity(sign);
            helper.assertTrue(text != null && com.dwinovo.numen.plugins.minecraft.tools.Signs.lines(text.getFrontText())
                            .equals(java.util.List.of("Hello", "World", "", "")), "the front of the sign is not written");
        });
        steps = call(helper, steps, companion,
                "minecraft.use.sign(" + xyz(sign) + ", {\"Behind\"}, {back = true})");
        steps.thenWaitUntil(() -> {
            var text = (net.minecraft.world.level.block.entity.SignBlockEntity) helper.getLevel().getBlockEntity(sign);
            helper.assertTrue(com.dwinovo.numen.plugins.minecraft.tools.Signs.lines(text.getBackText())
                            .equals(java.util.List.of("Behind", "", "", "")), "the back of the sign is not written");
            helper.assertTrue(com.dwinovo.numen.plugins.minecraft.tools.Signs.lines(text.getFrontText()).get(0).equals("Hello"),
                    "writing the back changed the front");
        }).thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    /** 涂过蜡的牌子原版不让改:回执如实说牌子涂了蜡,牌子上的字没动。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_interact")
    public static void use_sign_on_a_waxed_sign_says_it_is_waxed(GameTestHelper helper) {
        BlockPos sign = helper.absolutePos(new BlockPos(5, 2, 4));
        helper.getLevel().setBlockAndUpdate(sign, Blocks.OAK_SIGN.defaultBlockState());
        var entity = (net.minecraft.world.level.block.entity.SignBlockEntity) helper.getLevel().getBlockEntity(sign);
        entity.updateText(t -> t.setMessage(0, net.minecraft.network.chat.Component.literal("Keep")), true);
        entity.setWaxed(true);
        NumenPlayer companion = spawnAt(helper, "gametest_vandal", new BlockPos(3, 2, 4), false);
        ToolRun write = lua(companion, "minecraft.use.sign(" + xyz(sign) + ", {\"Vandalised\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(write.done() && !write.succeeded() && write.outcome().contains("waxed"),
                    "the refusal does not say the sign is waxed: " + write.outcome());
            helper.assertTrue(com.dwinovo.numen.plugins.minecraft.tools.Signs.lines(entity.getFrontText()).get(0).equals("Keep"),
                    "the waxed sign's text changed");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 盔甲架:点头部放上头盔,点腿部放上护腿,空手点腿部取下护腿——头盔还在架子上,护腿回到她手里。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_interact")
    public static void use_entity_at_a_part_dresses_and_undresses_an_armor_stand(GameTestHelper helper) {
        var stand = net.minecraft.world.entity.EntityType.ARMOR_STAND.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 4));
        stand.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        helper.getLevel().addFreshEntity(stand);
        NumenPlayer companion = spawnAt(helper, "gametest_dresser", new BlockPos(6, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_HELMET));
        companion.getInventory().add(new ItemStack(Items.IRON_LEGGINGS));

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "minecraft.use.entity(" + ref(stand) + ", {item = \"minecraft:iron_helmet\", at = \"head\"})");
        steps = call(helper, steps, companion, "minecraft.use.entity(" + ref(stand) + ", {item = \"minecraft:iron_leggings\", at = \"legs\"})");
        steps = steps.thenWaitUntil(() -> helper.assertTrue(
                stand.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.IRON_HELMET)
                        && stand.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).is(Items.IRON_LEGGINGS),
                "the stand is not wearing the helmet and the leggings"));
        steps = call(helper, steps, companion, "minecraft.use.entity(" + ref(stand) + ", {at = \"legs\"})");
        steps.thenWaitUntil(() -> {
            helper.assertTrue(stand.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).isEmpty()
                            && stand.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.IRON_HELMET),
                    "the leggings are still on the stand, or the helmet came off too");
            helper.assertTrue(companion.getMainHandItem().is(Items.IRON_LEGGINGS), "the leggings are not in her hand");
        }).thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    /** 穿着鞘翅在空中起飞:原版确认她进入滑翔。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_interact")
    public static void use_glide_starts_gliding_in_the_air(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_glider", new BlockPos(8, 2, 8), false);
        companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        drop(helper, companion, new BlockPos(8, 60, 8));

        Steps steps = steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(!companion.onGround(), "she has not left the ground"));
        steps = call(helper, steps, companion, "minecraft.use.glide()");
        steps.thenWaitUntil(() -> helper.assertTrue(companion.isFallFlying(), "she is not gliding"))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }
}
