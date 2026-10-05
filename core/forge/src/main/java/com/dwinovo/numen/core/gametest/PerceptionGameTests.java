package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.GameTestDontPrefix;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 感知:{@code scan} 组({@code numen.scan.block}、{@code numen.scan.container}、{@code numen.scan.map}、{@code numen.scan.entities}、
 * {@code numen.scan.blocks})、{@code status} 组({@code numen.status.self}、{@code numen.status.world}、{@code numen.status.owner}),以及
 * {@code numen.inv.recipes}。这些不动世界,测的是回执说的是不是眼前的真事;提升成快捷工具的
 * 动作,同一刻从工具与从 {@code command} 读到的一字不差(同源)。
 */
@GameTestHolder(value = Constants.MOD_ID, namespace = Constants.MOD_ID)
@GameTestDontPrefix
public class PerceptionGameTests {

    /** 感知批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_perception")
    public static void preparePerceptionBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 看一格方块:是什么、够不够得着。手边那块石头够得着,场地另一头那块够不着。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void inspect_block_names_the_block_and_its_reach(GameTestHelper helper) {
        BlockPos near = helper.absolutePos(new BlockPos(5, 2, 3));
        BlockPos far = helper.absolutePos(new BlockPos(14, 2, 14));
        helper.getLevel().setBlockAndUpdate(near, Blocks.STONE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(far, Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_inspector", new BlockPos(3, 2, 3), false);
        ToolRun atNear = lua(companion, "numen.scan.block(" + xyz(near) + ")");
        ToolRun atFar = lua(companion, "numen.scan.block(" + xyz(far) + ")");
        ToolRun viaCommand = lua(companion, "numen.scan.block({x = " + near.getX() + ", y = " + near.getY() + ", z = " + near.getZ() + "})");

        succeedWhen(helper, () -> {
            JsonObject n = json(atNear);
            JsonObject f = json(atFar);
            helper.assertTrue(n.get("name").getAsString().equals("minecraft:stone") && n.get("in_reach").getAsBoolean(),
                    "the stone beside her is not reported as stone within reach: " + atNear.reply());
            helper.assertTrue(!f.get("in_reach").getAsBoolean(),
                    "the stone across the site is reported within reach: " + atFar.reply());
            helper.assertTrue(atNear.reply().equals(viaCommand.reply()),
                    "scan_block and scan block read differently: " + atNear.reply() + " / " + viaCommand.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 不开界面读箱子里有什么:五颗钻石,报出来就是五颗钻石。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void inspect_block_storage_reads_a_chest_without_opening_it(GameTestHelper helper) {
        BlockPos chest = chestWithDiamonds(helper, new BlockPos(5, 2, 3), 5);
        NumenPlayer companion = spawnAt(helper, "gametest_auditor", new BlockPos(3, 2, 3), false);
        ToolRun storage = lua(companion, "numen.scan.container({x = " + chest.getX() + ", y = " + chest.getY() + ", z = " + chest.getZ() + "})");

        succeedWhen(helper, () -> {
            helper.assertTrue(storage.succeeded() && storage.reply().contains("diamond")
                            && storage.reply().contains("5"),
                    "the chest's diamonds are not in the reply: " + storage.reply());
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "reading the chest opened it");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 俯视图:以她为中心,东边两格的墙是 #,北边两格的台阶是 ^,西边两格的水是 ~。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void look_around_draws_walls_steps_and_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(10, 2, 8)), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(10, 3, 8)), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8, 2, 6)), Blocks.STONE.defaultBlockState());
        // 地板只有一层,底下是空的:先垫一块,水才不会漏下去
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(6, 0, 8)), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(6, 1, 8)), Blocks.WATER.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_surveyor", new BlockPos(8, 2, 8), false);

        java.util.concurrent.atomic.AtomicReference<ToolRun> map = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ToolRun> viaCommand = new java.util.concurrent.atomic.AtomicReference<>();
        // 图以她脚下那一格为中心,落地之前那一格还没定
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(companion.onGround(), "she has not landed"))
                .thenExecute(() -> {
                    map.set(lua(companion, "numen.scan.map()"));
                    viaCommand.set(lua(companion, "numen.scan.map()"));
                })
                .thenWaitUntil(() -> {
                    String m = rows(map.get());
                    helper.assertTrue(cell(m, 2, 0) == '#', "the wall two east is not #: \n" + m);
                    helper.assertTrue(cell(m, 0, -2) == '^', "the step two north is not ^: \n" + m);
                    helper.assertTrue(cell(m, -2, 0) == '~', "the water two west is not ~: \n" + m);
                    helper.assertTrue(m.equals(rows(viaCommand.get())),
                            "two numen.scan.map calls draw differently: \n" + m + "\n" + viaCommand.get().reply());
                })
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 周围的实体:一头猪列在里面,带着能交给 attack 的 id;只看敌对的时候它不在。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void scan_nearby_entities_lists_a_pig_and_filters_by_kind(GameTestHelper helper) {
        Pig pig = EntityType.PIG.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 3));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        NumenPlayer companion = spawnAt(helper, "gametest_watcher", new BlockPos(3, 2, 3), false);
        ToolRun all = lua(companion, "numen.scan.entities(\"all\", {radius = " + 12 + "})");
        ToolRun hostile = lua(companion, "numen.scan.entities(\"hostile\", {radius = " + 12 + "})");
        ToolRun viaCommand = lua(companion, "numen.scan.entities(\"all\", {radius = 12})");

        succeedWhen(helper, () -> {
            helper.assertTrue(listsEntity(all, pig) && all.reply().contains("pig"),
                    "the pig is not listed with its id: " + all.reply());
            helper.assertTrue(!listsEntity(hostile, pig),
                    "the pig is listed as hostile: " + hostile.reply());
            helper.assertTrue(all.reply().equals(viaCommand.reply()),
                    "scan_entities and scan entities list differently: " + all.reply() + " / "
                            + viaCommand.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** {@code numen.scan.entities} 的这一页有没有列出这只实体(按它的运行期编号)。 */
    private static boolean listsEntity(ToolRun scan, net.minecraft.world.entity.Entity entity) {
        return rowOf(scan, entity) != null;
    }

    /** {@code numen.scan.entities} 的这一页里这只实体的那一行;没列出为 null。 */
    private static com.google.gson.JsonObject rowOf(ToolRun scan, net.minecraft.world.entity.Entity entity) {
        for (var row : valueIn(scan.reply()).getAsJsonArray()) {
            if (row.getAsJsonObject().get("id").getAsInt() == entity.getId()) {
                return row.getAsJsonObject();
            }
        }
        return null;
    }

    /**
     * 列出的狼标出是谁的:她自己驯服的是 {@code you},她主人的是 {@code your owner},别的玩家的(名字查不到)是 UUID,
     * 野狼不写。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void scan_entities_says_whose_each_wolf_is(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_petcounter", new BlockPos(3, 2, 3), false);
        var hers = InteractGameTests.wolfAt(helper, new BlockPos(6, 2, 3));
        hers.tame(companion);
        var owners = InteractGameTests.wolfAt(helper, new BlockPos(3, 2, 6));
        owners.setTame(true);
        owners.setOwnerUUID(companion.getOwnerUuid());
        var strangers = InteractGameTests.wolfAt(helper, new BlockPos(6, 2, 6));
        java.util.UUID stranger = java.util.UUID.randomUUID();
        strangers.setTame(true);
        strangers.setOwnerUUID(stranger);
        var wild = InteractGameTests.wolfAt(helper, new BlockPos(1, 2, 1));
        ToolRun scan = lua(companion, "numen.scan.entities(\"passive\", {radius = 8})");

        succeedWhen(helper, () -> {
            var rows = java.util.stream.Stream.of(hers, owners, strangers, wild).map(w -> rowOf(scan, w)).toList();
            helper.assertTrue(rows.stream().allMatch(java.util.Objects::nonNull),
                    "not every wolf is listed: " + scan.reply());
            helper.assertTrue(rows.get(0).has("owner") && rows.get(0).get("owner").getAsString().equals("you"),
                    "her own wolf is not marked as hers: " + rows.get(0));
            helper.assertTrue(rows.get(1).has("owner")
                            && rows.get(1).get("owner").getAsString().equals("your owner"),
                    "her owner's wolf is not marked as his: " + rows.get(1));
            helper.assertTrue(rows.get(2).has("owner")
                            && rows.get(2).get("owner").getAsString().equals(stranger.toString()),
                    "a stranger's wolf does not name its owner: " + rows.get(2));
            helper.assertTrue(!rows.get(3).has("owner"), "a wild wolf is marked as someone's: " + rows.get(3));
            java.util.stream.Stream.of(hers, owners, strangers, wild).forEach(net.minecraft.world.entity.Entity::discard);
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 她自己的状态:手里的剑、背包用了几格、血与饥饿都照实报。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void get_self_status_reports_what_she_carries(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_selfie", new BlockPos(3, 2, 3), false);
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        companion.getInventory().add(new ItemStack(Items.DIAMOND, 3));
        ToolRun status = lua(companion, "numen.status.self()");
        ToolRun viaCommand = lua(companion, "numen.status.self()");

        succeedWhen(helper, () -> {
            JsonObject s = json(status);
            helper.assertTrue(s.get("hands").toString().contains("minecraft:iron_sword"),
                    "the sword in her hand is not reported: " + status.reply());
            helper.assertTrue(s.getAsJsonObject("backpack_slots").get("used").getAsInt() == 2,
                    "the backpack does not count two used slots: " + status.reply());
            helper.assertTrue(s.get("hp").getAsFloat() == companion.getHealth(), "hp is not her health");
            helper.assertTrue(status.reply().equals(viaCommand.reply()),
                    "status_self and status self read differently: " + status.reply() + " / "
                            + viaCommand.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 世界信息:主世界、白天、晴天。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void get_world_info_tells_day_and_clear_weather(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_skywatcher", new BlockPos(3, 2, 3), false);
        ToolRun info = lua(companion, "numen.status.world()");

        succeedWhen(helper, () -> {
            JsonObject w = json(info);
            helper.assertTrue(w.get("dimension").getAsString().equals("minecraft:overworld")
                            && w.get("is_bright_outside").getAsBoolean()
                            && w.get("weather").getAsString().equals("clear"),
                    "the world info is not overworld, day and clear: " + info.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 主人不在线就说不在线;主人在场就报名字和离她多远。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void get_owner_status_reports_whether_the_owner_is_here(GameTestHelper helper) {
        NumenPlayer alone = spawnAt(helper, "gametest_orphan", new BlockPos(3, 2, 3), false);
        NumenPlayer companion = spawnAt(helper, "gametest_ward", new BlockPos(3, 2, 8), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_guardian");
        ToolRun absent = lua(alone, "numen.status.owner()");
        ToolRun present = lua(companion, "numen.status.owner()");
        ToolRun viaCommand = lua(companion, "numen.status.owner()");

        succeedWhen(helper, () -> {
            helper.assertTrue(!json(absent).get("online").getAsBoolean(),
                    "an absent owner is reported online: " + absent.reply());
            JsonObject p = json(present);
            helper.assertTrue(p.get("online").getAsBoolean() && p.get("name").getAsString().equals("gametest_guardian")
                            && p.has("distance"),
                    "the present owner is not reported with name and distance: " + present.reply());
            helper.assertTrue(present.reply().equals(viaCommand.reply()),
                    "status_owner and status owner read differently: " + present.reply() + " / "
                            + viaCommand.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), alone);
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
            leave(owner);
        });
    }

    /** 查配方:铁锭既能炼(熔炉)也能合(铁块、铁粒),两种都列出来并标明在哪做;脚本拿到的每一条带着编号和工位。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void lookup_recipe_lists_every_station(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_scholar", new BlockPos(3, 2, 3), false);
        ToolRun recipe = lua(companion, "numen.inv.recipes(\"minecraft:iron_ingot\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(recipe.succeeded(), "the recipes were not read: " + recipe.reply());
            java.util.Set<String> stations = new java.util.HashSet<>();
            for (var one : valueIn(recipe.reply()).getAsJsonArray()) {
                var r = one.getAsJsonObject();
                helper.assertTrue(r.get("id").getAsString().contains(":")
                                && r.get("item").getAsString().equals("minecraft:iron_ingot"),
                        "a recipe has no id or names another item: " + r);
                stations.add(r.get("station").getAsString());
            }
            helper.assertTrue(stations.contains("smelting") && stations.contains("crafting"),
                    "the recipes' stations are not both there: " + stations);
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 同源:找方块从 scan_blocks 与 numen.scan.blocks 读到同一份团。标签照原版写法({@code #minecraft:logs})认,一个 id 一个标签
     * 同一次找;只是看,不存、没有编号,两份回执一字不差。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_perception")
    public static void scan_blocks_reads_the_same_from_the_tool_and_the_command(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos gold = helper.absolutePos(new BlockPos(6, 2, 3));
        BlockPos log = helper.absolutePos(new BlockPos(3, 2, 7));
        level.setBlockAndUpdate(gold, Blocks.GOLD_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(log, Blocks.OAK_LOG.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_twin_eyes", new BlockPos(3, 2, 3), false);
        java.util.concurrent.atomic.AtomicReference<ToolRun> viaTool = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ToolRun> viaCommand = new java.util.concurrent.atomic.AtomicReference<>();

        // 团的中心是她脚下那一格,落地之前那一格还没定
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(companion.onGround(), "she has not landed"))
                .thenExecute(() -> viaTool.set(lua(companion, "numen.scan.blocks(\"minecraft:gold_block\", \"#minecraft:logs\", {radius = " + 8 + "})")))
                .thenWaitUntil(() -> helper.assertTrue(viaTool.get().reply() != null, "scan_blocks has not answered"))
                .thenExecute(() -> viaCommand.set(lua(companion, "numen.scan.blocks(\"minecraft:gold_block\", \"#minecraft:logs\", {radius = 8})")))
                .thenWaitUntil(() -> {
                    String tool = viaTool.get().reply();
                    String line = viaCommand.get().reply();
                    helper.assertTrue(line != null, "scan blocks has not answered");
                    helper.assertTrue(clusterHolding(clustersIn(tool), gold) != null && clusterHolding(clustersIn(tool), log) != null,
                            "the gold block and the log are not both found: " + tool);
                    helper.assertTrue(tool.equals(line),
                            "scan_blocks and scan blocks find differently: " + tool + " / " + line);
                })
                .thenExecute(() -> {
                    level.removeBlock(gold, false);
                    level.removeBlock(log, false);
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /** 这次调用交给脚本的数据。 */
    private static JsonObject json(ToolRun run) {
        return dataIn(run.reply());
    }

    /** {@code numen.scan.map} 交回的图,一行一行。 */
    private static String rows(ToolRun map) {
        return map.field("rows") instanceof List<?> rows
                ? String.join("\n", rows.stream().map(String::valueOf).toList()) : "";
    }

    /**
     * 俯视图里离她 {@code (dx, dz)} 的那一格:东为 +x、南为 +z。图的每一行格子之间隔一个空格,{@code @} 所在的
     * 那一行、那一列就是她。
     */
    private static char cell(String map, int dx, int dz) {
        List<String> rows = map.lines().filter(l -> !l.isEmpty() && l.charAt(1) == ' '
                && !l.startsWith("scan_around") && !l.startsWith("legend")).toList();
        int row = -1;
        int col = -1;
        for (int r = 0; r < rows.size(); r++) {
            int c = rows.get(r).indexOf('@');
            if (c >= 0) {
                row = r;
                col = c;
            }
        }
        return rows.get(row + dz).charAt(col + 2 * dx);
    }

    /** 读一块石头和一格空气的存储:石头交回一个空的存储表;空气直接失败,说那儿什么都没有。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void inspect_block_storage_on_stone_and_on_air(GameTestHelper helper) {
        BlockPos stone = helper.absolutePos(new BlockPos(5, 2, 3));
        helper.getLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        BlockPos air = helper.absolutePos(new BlockPos(5, 3, 3));
        NumenPlayer companion = spawnAt(helper, "gametest_prober", new BlockPos(3, 2, 3), false);
        ToolRun onStone = lua(companion, "numen.scan.container({x = " + stone.getX() + ", y = " + stone.getY() + ", z = " + stone.getZ() + "})");
        ToolRun onAir = lua(companion, "numen.scan.container({x = " + air.getX() + ", y = " + air.getY() + ", z = " + air.getZ() + "})");

        succeedWhen(helper, () -> {
            helper.assertTrue(onStone.succeeded() && List.of().equals(onStone.field("storage")),
                    "stone was not reported as holding nothing: " + onStone.reply());
            helper.assertTrue(!onAir.succeeded() && onAir.reply().contains("is air"),
                    "air was not reported as nothing to read: " + onAir.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * {@code numen.scan.sight}:她眼前一块金块,中间什么都没有,看得见;一堵石墙后面的金块与一头牛都看不见,挡着的是那堵墙的一格。
     * 只读:墙与金块原样。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void scan_sight_says_what_is_in_the_way(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos open = helper.absolutePos(new BlockPos(6, 2, 7));
        BlockPos hidden = helper.absolutePos(new BlockPos(10, 2, 3));
        level.setBlockAndUpdate(open, Blocks.GOLD_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(hidden, Blocks.GOLD_BLOCK.defaultBlockState());
        int wallX = helper.absolutePos(new BlockPos(8, 2, 0)).getX();
        for (int y = 2; y <= 5; y++) {
            for (int z = 0; z <= 9; z++) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8, y, z)), Blocks.STONE.defaultBlockState());
            }
        }
        var cow = net.minecraft.world.entity.EntityType.COW.create(level);
        helper.assertTrue(cow != null, "the cow did not spawn");
        BlockPos pen = helper.absolutePos(new BlockPos(11, 2, 6));
        cow.moveTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0.0f, 0.0f);
        cow.setNoAi(true);
        level.addFreshEntity(cow);
        NumenPlayer companion = spawnAt(helper, "gametest_lookout", new BlockPos(3, 2, 7), false);
        ToolRun seen = lua(companion, "return {open = numen.scan.sight(" + xyz(open) + "), hidden = numen.scan.sight("
                + xyz(hidden) + "), cow = numen.scan.sight(" + cow.getId() + ")}");

        succeedWhen(helper, () -> {
            helper.assertTrue(seen.receipt() != null, "numen.scan.sight has not answered");
            helper.assertTrue(seen.ranToTheEnd(), "numen.scan.sight failed: " + seen.receipt());
            var got = seen.data().getAsJsonObject("returned");
            helper.assertTrue(got.getAsJsonObject("open").get("visible").getAsBoolean()
                            && !got.getAsJsonObject("open").has("blocked_by"),
                    "the gold block right in front of her is not seen: " + got);
            for (String behind : List.of("hidden", "cow")) {
                var one = got.getAsJsonObject(behind);
                helper.assertTrue(!one.get("visible").getAsBoolean() && one.has("blocked_by")
                                && "minecraft:stone".equals(one.getAsJsonObject("blocked_by").get("name").getAsString())
                                && one.getAsJsonObject("blocked_by").getAsJsonObject("pos").get("x").getAsInt() == wallX,
                        "the " + behind + " behind the wall is not reported as hidden by it: " + got);
            }
            helper.assertTrue(level.getBlockState(hidden).is(Blocks.GOLD_BLOCK)
                    && level.getBlockState(helper.absolutePos(new BlockPos(8, 2, 3))).is(Blocks.STONE), "looking changed the world");
            CompanionFactory.despawn(level.getServer(), companion);
            cow.discard();
        });
    }

    /** 查一样合成、烧炼都做不出来的东西(末影珍珠):交回空表,没有配方。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void lookup_recipe_for_something_not_made_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_curious", new BlockPos(3, 2, 3), false);
        ToolRun recipe = lua(companion, "numen.inv.recipes(\"minecraft:ender_pearl\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(recipe.succeeded() && List.of().equals(recipe.value()),
                    "ender pearls are given a recipe: " + recipe.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 查一个不存在的物品 id:按参数错误退回,说出是哪个 id。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void lookup_recipe_for_an_unknown_item_is_rejected(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_misspeller", new BlockPos(3, 2, 3), false);
        ToolRun recipe = lua(companion, "numen.inv.recipes(\"minecraft:no_such_item\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(!recipe.succeeded() && recipe.outcome().contains("there is no item minecraft:no_such_item")
                            && recipe.outcome().contains("\nusage: numen.inv.recipes("),
                    "the unknown id was not rejected by name: " + recipe.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 找方块的搜索按工作量收工,和每刻分到多少时间无关:每一刻都先让别的搜索把整刻的时间上限吃光,她这次
     * 查询照样在有限的刻数里回来,带着结论(看满节数上限而截断,或走完)——不会因为机器慢、别人忙而永远挂着。
     * mine"图回来之前不下够不着的结论"靠的就是这一条:图一定会回来。
     *
     * <p>单独一个批次:这条用例每刻都把共享的时间上限耗尽,和别的用例同批会拖慢它们的搜索。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_scan_budget")
    public static void a_block_search_returns_even_when_every_tick_is_already_spent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sponge = helper.absolutePos(new BlockPos(12, 2, 12));
        level.setBlockAndUpdate(sponge, Blocks.SPONGE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_starved", new BlockPos(3, 2, 3), false);
        java.util.concurrent.atomic.AtomicReference<com.dwinovo.numen.core.scan.BlockSearch.ScanResult> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        // 测试刻在服务端刻里先于刻末的找方块推进:这里先把本刻的时间上限整个耗掉
        helper.onEachTick(() -> {
            if (result.get() != null) {
                return;
            }
            try (com.dwinovo.numen.core.scan.SearchBudget.Slice hog =
                         com.dwinovo.numen.core.scan.SearchBudget.slice(level.getServer())) {
                while (com.dwinovo.numen.core.scan.SearchBudget.withinTime()) {
                    Thread.onSpinWait();
                }
            }
        });
        com.dwinovo.numen.core.scan.BlockSearch.start(companion.getUUID(), level, companion.blockPosition(), 24,
                com.dwinovo.numen.core.scan.BlockSearch.MAX_COLLECT, java.util.Set.of(Blocks.SPONGE), result::set);

        succeedWhen(helper, () -> {
            var res = result.get();
            helper.assertTrue(res != null, "the search never came back while every tick was already spent");
            helper.assertTrue(res.matches().stream().anyMatch(h -> h.pos().equals(sponge)),
                    "the sponge on the site was not found");
            helper.assertTrue(res.sectionCapHit() || !res.stoppedEarly() && !res.collectCapHit(),
                    "the search came back without a conclusion about its coverage");
            level.removeBlock(sponge, false);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
